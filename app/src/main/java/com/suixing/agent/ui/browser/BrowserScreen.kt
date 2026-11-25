package com.suixing.agent.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.suixing.agent.R
import com.suixing.agent.tools.resolveUserPath
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.PageTopBarAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BrowserPage(val url: String, val toolRequest: BrowserOpenRequest?)

private const val BROWSER_TITLE = "浏览器"

private val BrowserTarget.inputAddress: String
    get() = when (this) {
        is BrowserTarget.Web -> url
        is BrowserTarget.Document -> path
    }

internal class BrowserSession(private var saved: Bundle? = null) {
    val openingDrawn = CompletableDeferred<Unit>()

    suspend fun awaitEngine(startEngine: suspend () -> Unit) {
        openingDrawn.await()
        withFrameNanos { }
        startEngine()
    }

    val files = BrowserLocalFiles(saved?.getStringArrayList("browser-roots").orEmpty())
    var view: WebView? by mutableStateOf(null)
    var title by mutableStateOf(BROWSER_TITLE)
    var address by mutableStateOf("")
    var editing by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    private var pageCommitted by mutableStateOf(false)
    private var pendingOpeningFrame: OpeningFrame? = null
    private var replacedUrl: String? = null
    private var openingFailed = false
    val showOpening: Boolean get() = !pageCommitted && error == null
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableStateOf<BrowserPage?>(null)
    var loadedPage: BrowserPage? = null

    fun titleChanged(view: WebView, title: String?) {
        if (this.view === view) this.title = title?.takeIf { it.isNotBlank() } ?: BROWSER_TITLE
    }

    fun save(): Bundle = (view?.let { web -> Bundle().also { web.saveState(it) } } ?: saved?.let(::Bundle) ?: Bundle()).apply {
        putStringArrayList("browser-roots", files.savedRoots())
    }

    fun restore(view: WebView): Boolean {
        val previous = saved ?: return false
        val history = view.restoreState(previous) ?: return false
        val item = history.currentItem
        if (!editing) address = files.displayUrl(item?.url.orEmpty())
        titleChanged(view, item?.title)
        val index = history.currentIndex
        canBack = index > 0
        canForward = index >= 0 && index < history.size - 1
        return true
    }

    fun load(view: WebView, page: BrowserPage) {
        resetOpeningFrame()
        replacedUrl = view.url
        loadedPage = page
        view.loadUrl(page.url)
    }

    fun reload(): Boolean {
        val view = view ?: return false
        resetOpeningFrame()
        replacedUrl = view.url
        view.reload()
        return true
    }

    fun pageStarted(view: WebView, url: String?) {
        if (this.view !== view) return
        resetOpeningFrame()
        error = null
        title = BROWSER_TITLE
        if (!editing) address = files.displayUrl(url.orEmpty())
    }

    fun historyUpdated(view: WebView, url: String?) {
        if (this.view !== view) return
        val editingAddress = editing
        // Reloads and redirects can commit the same URL as the replaced document.
        if (url != null && (!editingAddress || url == replacedUrl) && view.url == url) {
            if (url == replacedUrl) replacedUrl = null
            if (!editingAddress) address = files.displayUrl(url)
        }
        canBack = view.canGoBack()
        canForward = view.canGoForward()
    }

    private fun matchesPage(view: WebView, url: String): Boolean =
        this.view === view && url != replacedUrl && view.url == url

    fun pageFinished(view: WebView, url: String?) {
        if (url == null || !matchesPage(view, url)) return
        if (title == BROWSER_TITLE) titleChanged(view, view.title)
        showPageWhenReady(view, url)
        val request = loadedPage?.toolRequest?.takeIf { !openingFailed && isWebUrl(url) }
        val editingAddress = editing
        if (!editingAddress || request != null) {
            val displayed = files.displayUrl(url)
            if (!editingAddress) address = displayed
            request?.read(view, url, displayed)
        }
    }

    fun pageFailed(view: WebView, message: String) {
        if (this.view !== view) return
        pendingOpeningFrame = null
        openingFailed = true
        error = message
        loadedPage?.toolRequest?.fail(message)
    }

    fun showPageWhenReady(view: WebView, url: String?) {
        if (url == null || pageCommitted || openingFailed || pendingOpeningFrame?.url == url ||
            !matchesPage(view, url)
        ) return
        val callback = OpeningFrame(view, url)
        pendingOpeningFrame = callback
        view.postVisualStateCallback(0, callback)
    }

    private inner class OpeningFrame(val web: WebView, val url: String) : WebView.VisualStateCallback() {
        override fun onComplete(requestId: Long) {
            if (pendingOpeningFrame !== this) return
            pendingOpeningFrame = null
            if (matchesPage(web, url)) {
                web.visibility = View.VISIBLE
                pageCommitted = true
            }
        }
    }

    private fun resetOpeningFrame() {
        pendingOpeningFrame = null
        openingFailed = false
    }

    fun release(view: WebView) {
        resetOpeningFrame()
        replacedUrl = null
        saved = save()
        view.stopLoading()
        view.destroy()
        this.view = null
        loadedPage = null
        pageCommitted = false
    }

    companion object {
        val saver = Saver<BrowserSession, Bundle>(save = { it.save() }, restore = { BrowserSession(it) })
    }
}

private suspend fun startBrowserEngine(context: Context) = suspendCancellableCoroutine<Unit> { ready ->
    val config = WebViewStartUpConfig.Builder(Dispatchers.IO.asExecutor()).build()
    WebViewCompat.startUpWebView(
        context.applicationContext,
        config,
        object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
            override fun onResult(result: WebViewStartUpResult) = ready.resume(Unit)

            override fun onError(error: WebViewStartupException) =
                ready.resumeWithException(IllegalStateException("无法启动系统 WebView", error))
        },
    )
}

@Composable
internal fun BrowserScreen(
    target: BrowserTarget,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    toolRequest: BrowserOpenRequest? = null,
) {
    val session = rememberSaveable(target, saver = BrowserSession.saver) {
        BrowserSession().apply { address = target.inputAddress }
    }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val navigation = remember { arrayOfNulls<Job>(1) }
    fun navigate(target: BrowserTarget, request: BrowserOpenRequest? = null) {
        navigation[0]?.cancel()
        if (request == null) toolRequest?.fail("用户切换了网页")
        session.error = null
        session.address = target.inputAddress
        navigation[0] = scope.launch {
            try {
                keyboard?.hide()
                focus.clearFocus()
                val url = when (target) {
                    is BrowserTarget.Web -> target.url
                    is BrowserTarget.Document -> withContext(Dispatchers.IO) {
                        session.files.open(target, resolveUserPath(context, target.path))
                    }
                }
                if (session.view == null) session.awaitEngine { startBrowserEngine(context) }
                val page = BrowserPage(url, request)
                session.address = session.files.displayUrl(page.url)
                session.page = page
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = failure.message ?: "无法打开这个网页"
                session.error = message
                request?.fail(message)
            }
        }
    }
    LaunchedEffect(target, toolRequest) { navigate(target, toolRequest) }
    DisposableEffect(toolRequest) {
        onDispose { toolRequest?.fail("浏览器已关闭") }
    }
    BackHandler {
        val web = session.view
        if (web?.canGoBack() == true) {
            toolRequest?.fail("用户切换了网页")
            web.goBack()
        } else onBack()
    }
    BrowserLifecycleObserver(session)

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding().imePadding(),
    ) {
        BrowserToolbar(session, onBack, onReload = {
            toolRequest?.fail("用户重新加载了网页")
            session.error = null
            if (!session.reload()) navigate(target)
        }, onNavigate = { navigate(it) })
        BrowserContent(session, uriHandler, { navigate(it) }, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun BrowserLifecycleObserver(session: BrowserSession) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            val view = session.view
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun BrowserToolbar(
    session: BrowserSession,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onNavigate: (BrowserTarget) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).zIndex(1f).drawWithContent {
            drawContent()
            if (!session.openingDrawn.isCompleted) session.openingDrawn.complete(Unit)
        },
    ) {
        BrowserTopBar(session, onBack, onReload)
        BrowserAddressBar(session, onNavigate)
    }
}

@Composable
private fun BrowserTopBar(
    session: BrowserSession,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    PageTopBar(title = session.title, onBack = onBack) {
        PageTopBarAction(ImageVector.vectorResource(R.drawable.ic_chevron_left), "后退", {
            session.loadedPage?.toolRequest?.fail("用户切换了网页")
            session.view?.goBack()
        }, enabled = session.canBack, glyphSize = 22.dp)
        PageTopBarAction(ImageVector.vectorResource(R.drawable.ic_chevron_right), "前进", {
            session.loadedPage?.toolRequest?.fail("用户切换了网页")
            session.view?.goForward()
        }, enabled = session.canForward, glyphSize = 22.dp)
        TextButton(onClick = onReload) { Text("刷新") }
    }
}

@Composable
private fun BrowserAddressBar(
    session: BrowserSession,
    onNavigate: (BrowserTarget) -> Unit,
) {
    fun submit() {
        val destination = browserAddressTarget(session.address)
        if (destination != null) onNavigate(destination) else session.error = "请输入网址或 HTML 文件路径"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactOutlinedTextField(
            value = session.address,
            onValueChange = { session.address = it },
            modifier = Modifier.weight(1f).onFocusChanged { session.editing = it.isFocused },
            placeholder = { Text("输入网址或 HTML 文件路径") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        TextButton(onClick = { submit() }) { Text("前往") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserContent(
    session: BrowserSession,
    uriHandler: UriHandler,
    navigate: (BrowserTarget) -> Unit,
    modifier: Modifier,
) {
    val loading by remember(session) { derivedStateOf { session.progress < 100 } }
    Column(modifier.clipToBounds()) {
        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val page = session.page
            if (page != null) AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { context ->
                    WebView(context).apply {
                        visibility = View.INVISIBLE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                if (session.view === view) session.progress = newProgress
                            }
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                session.titleChanged(view, title)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                session.files.response(request.url.toString())

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && request.hasGesture()) {
                                    session.loadedPage?.toolRequest?.fail("用户切换了网页")
                                }
                                val url = request.url.toString()
                                val scheme = request.url.scheme
                                if (isWebUrl(url) || scheme == "about" || scheme == "data" || scheme == "blob") return false
                                if (!request.isForMainFrame || !request.hasGesture()) return true
                                val document = browserTargetFromUri(url)
                                if (document != null) navigate(document) else {
                                    try { uriHandler.openUri(url) }
                                    catch (_: ActivityNotFoundException) { session.error = "没有可打开此链接的应用" }
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                session.pageStarted(view, url)
                            }

                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                session.showPageWhenReady(view, url)
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                session.historyUpdated(view, url)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                session.pageFinished(view, url)
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    session.pageFailed(view, "网页加载失败：${error.description}")
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
                            ) {
                                if (request.isForMainFrame) {
                                    session.loadedPage?.toolRequest?.fail("网页请求失败（HTTP ${errorResponse.statusCode}）")
                                }
                            }
                        }
                        session.view = this
                        if (page.toolRequest == null && session.restore(this)) session.loadedPage = page
                    }
                },
                update = { web ->
                    if (session.loadedPage !== page) {
                        session.load(web, page)
                    }
                },
                onRelease = { session.release(it) },
            )
            if (session.showOpening) {
                Text("正在打开网页…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
            }
            if (loading && session.error == null) LinearProgressIndicator(
                progress = { session.progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
    }
}
