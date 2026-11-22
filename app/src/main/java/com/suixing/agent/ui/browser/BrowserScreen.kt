package com.suixing.agent.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.os.Bundle
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
import com.suixing.agent.R
import com.suixing.agent.tools.resolveUserPath
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.PageTopBarAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BrowserPage(val url: String, val toolRequest: BrowserOpenRequest?)

private val BrowserTarget.inputAddress: String
    get() = when (this) {
        is BrowserTarget.Web -> url
        is BrowserTarget.Document -> path
    }

internal class BrowserSession(private var saved: Bundle? = null) {
    val openingDrawn = CompletableDeferred<Unit>()

    suspend fun awaitOpeningFrame() {
        openingDrawn.await()
        withFrameNanos { }
    }

    val files = BrowserLocalFiles(saved?.getStringArrayList("browser-roots").orEmpty())
    var view: WebView? by mutableStateOf(null)
    var title by mutableStateOf("浏览器")
    var address by mutableStateOf("")
    var editing by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    var pageCommitted by mutableStateOf(false)
    val showOpening: Boolean get() = !pageCommitted && error == null
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableStateOf<BrowserPage?>(null)
    var loadedPage: BrowserPage? = null

    fun update(view: WebView, url: String? = view.url) {
        if (!editing) address = files.displayUrl(url.orEmpty())
        title = view.title?.takeIf { it.isNotBlank() } ?: "浏览器"
        canBack = view.canGoBack()
        canForward = view.canGoForward()
    }

    fun save(): Bundle = (view?.let { web -> Bundle().also { web.saveState(it) } } ?: saved?.let(::Bundle) ?: Bundle()).apply {
        putStringArrayList("browser-roots", files.savedRoots())
    }

    fun restore(view: WebView): Boolean {
        val previous = saved ?: return false
        val history = view.restoreState(previous) ?: return false
        update(view, history.currentItem?.url)
        return true
    }

    fun release(view: WebView) {
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
                if (session.view == null) session.awaitOpeningFrame()
                keyboard?.hide()
                focus.clearFocus()
                val page = BrowserPage(when (target) {
                    is BrowserTarget.Web -> target.url
                    is BrowserTarget.Document -> withContext(Dispatchers.IO) {
                        session.files.open(target, resolveUserPath(context, target.path))
                    }
                }, request)
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = session.view
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding().imePadding(),
    ) {
        BrowserToolbar(session, onBack, onReload = {
            toolRequest?.fail("用户重新加载了网页")
            session.error = null
            session.view?.reload() ?: navigate(target)
        }, onNavigate = { navigate(it) })
        BrowserContent(session, uriHandler, { navigate(it) }, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun BrowserToolbar(
    session: BrowserSession,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onNavigate: (BrowserTarget) -> Unit,
) {
    fun submit() {
        val destination = browserAddressTarget(session.address)
        if (destination != null) onNavigate(destination) else session.error = "请输入网址或 HTML 文件路径"
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).zIndex(1f).drawWithContent {
            drawContent()
            if (!session.openingDrawn.isCompleted) session.openingDrawn.complete(Unit)
        },
    ) {
        PageTopBar(title = session.title, onBack = onBack) {
            PageTopBarAction(ImageVector.vectorResource(R.drawable.ic_chevron_left), "后退", {
                session.loadedPage?.toolRequest?.fail("用户切换了网页")
                session.view?.goBack()
            }, enabled = session.canBack)
            PageTopBarAction(ImageVector.vectorResource(R.drawable.ic_chevron_right), "前进", {
                session.loadedPage?.toolRequest?.fail("用户切换了网页")
                session.view?.goForward()
            }, enabled = session.canForward)
            TextButton(onClick = onReload) { Text("刷新") }
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserContent(
    session: BrowserSession,
    uriHandler: UriHandler,
    navigate: (BrowserTarget) -> Unit,
    modifier: Modifier,
) {
    val background = MaterialTheme.colorScheme.background
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { session.progress = newProgress }
                            override fun onReceivedTitle(view: WebView, title: String?) { session.update(view) }
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
                                session.error = null
                                session.update(view, url)
                            }

                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                if (session.pageCommitted) return
                                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        if (session.view === view) session.pageCommitted = true
                                    }
                                })
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                session.update(view)
                                if (url != null && isWebUrl(url) && session.view === view && view.url == url) {
                                    session.loadedPage?.toolRequest?.read(view, url, session.files.displayUrl(url))
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    val message = "网页加载失败：${error.description}"
                                    session.error = message
                                    session.loadedPage?.toolRequest?.fail(message)
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
                        session.loadedPage = page
                        web.loadUrl(page.url)
                    }
                },
                onRelease = { session.release(it) },
            )
            if (!session.pageCommitted) {
                Box(Modifier.matchParentSize().background(background), contentAlignment = Alignment.Center) {
                    if (session.showOpening) {
                        Text("正在打开网页…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (loading && session.error == null) LinearProgressIndicator(
                progress = { session.progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
    }
}
