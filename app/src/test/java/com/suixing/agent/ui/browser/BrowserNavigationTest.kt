package com.suixing.agent.ui.browser

import android.os.Bundle
import android.view.View
import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.WebView
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.platform.UriHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.URLDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTest {
    private val pageUrl = "https://example.com/page"

    @Test fun `first WebView waits for a drawn toolbar and a ready engine`() = runBlocking {
        withTimeout(1_000) {
            val clock = BroadcastFrameClock()
            val session = BrowserSession()
            val engineStarted = CompletableDeferred<Unit>()
            val engineReady = CompletableDeferred<Unit>()
            val opening = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                session.awaitEngine {
                    engineStarted.complete(Unit)
                    engineReady.await()
                }
            }
            clock.sendFrame(1L)
            assertFalse(engineStarted.isCompleted)
            session.openingDrawn.complete(Unit)
            yield()
            assertFalse(engineStarted.isCompleted)
            clock.sendFrame(2L)
            engineStarted.await()
            assertFalse(opening.isCompleted)
            engineReady.complete(Unit)
            opening.join()
        }
    }

    @Test fun `reopening during startup shares it and later openings reuse the ready engine`() = runBlocking {
        val engine = BrowserEngineStartup()
        lateinit var startup: CompletableDeferred<Unit>
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.awaitReady { startup = it }
        }
        first.cancelAndJoin()
        val reopened = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.awaitReady { error("The system is still starting WebView") }
        }
        assertTrue(startup.isActive)
        assertFalse(reopened.isCompleted)
        startup.complete(Unit)
        reopened.join()
        engine.awaitReady { error("WebView is already ready") }
    }

    @Test fun `a failed engine startup can be retried`() = runBlocking {
        val engine = BrowserEngineStartup()
        val failure = IllegalStateException("WebView startup failed")
        val reported = runCatching {
            engine.awaitReady { it.completeExceptionally(failure) }
        }.exceptionOrNull()
        assertTrue(reported is IllegalStateException)
        assertEquals(failure.message, reported?.message)
        engine.awaitReady { it.complete(Unit) }
        engine.awaitReady { error("A successful retry must be reused") }
    }

    @Test fun `opening feedback stays until WebView can draw the main page`() {
        val callback = slot<WebView.VisualStateCallback>()
        val web = mockk<WebView>(relaxUnitFun = true)
        every { web.url } returns pageUrl
        every { web.postVisualStateCallback(0, capture(callback)) } returns Unit
        val session = BrowserSession().apply { view = web }
        assertTrue(session.showOpening)
        session.progress = 80
        assertTrue(session.showOpening)
        session.progress = 100
        assertTrue(session.showOpening)
        session.showPageWhenReady(web, pageUrl)
        session.showPageWhenReady(web, pageUrl)
        verify(exactly = 1) { web.postVisualStateCallback(0, any()) }
        assertTrue(session.showOpening)
        verify(exactly = 0) { web.visibility = View.VISIBLE }
        callback.captured.onComplete(0)
        assertFalse(session.showOpening)
        verify(exactly = 1) { web.visibility = View.VISIBLE }
    }

    @Test fun `visual callback from a replaced WebView leaves the opening feedback visible`() {
        val callback = slot<WebView.VisualStateCallback>()
        val web = mockk<WebView>(relaxUnitFun = true)
        every { web.url } returns pageUrl
        every { web.postVisualStateCallback(0, capture(callback)) } returns Unit
        val session = BrowserSession().apply { view = web }
        session.showPageWhenReady(web, pageUrl)
        session.view = mockk()
        callback.captured.onComplete(0)
        assertTrue(session.showOpening)
        verify(exactly = 0) { web.visibility = View.VISIBLE }
    }

    @Test fun `an opening error replaces the loading feedback`() {
        val session = BrowserSession()
        session.error = "网页加载失败"
        assertFalse(session.showOpening)
        session.error = null
        assertTrue(session.showOpening)
    }

    @Test fun `toolbar title updates leave the address draft and history alone`() {
        val web = mockk<WebView>()
        val session = BrowserSession().apply {
            view = web
            editing = true
            address = "unfinished address"
        }
        session.titleChanged(web, "Document title")
        session.titleChanged(mockk(), "Old view title")
        assertEquals("Document title", session.title)
        assertEquals("unfinished address", session.address)
        verify(exactly = 0) { web.url }
        verify(exactly = 0) { web.title }
        verify(exactly = 0) { web.canGoBack() }
        verify(exactly = 0) { web.canGoForward() }

        every { web.canGoBack() } returns true
        every { web.canGoForward() } returns false
        session.historyUpdated(web, pageUrl)
        assertTrue(session.canBack)
        assertFalse(session.canForward)
        assertEquals("unfinished address", session.address)
        verify(exactly = 1) { web.canGoBack() }
        verify(exactly = 1) { web.canGoForward() }

        val clientRoute = "https://example.com/client-route"
        every { web.url } returns clientRoute
        session.editing = false
        session.historyUpdated(web, clientRoute)
        assertEquals(clientRoute, session.address)
    }

    @Test fun `navigation and reload cancel pending visual callbacks on the same WebView`() {
        val nextUrl = "https://example.com/next"
        var currentUrl: String? = null
        val callbacks = mutableListOf<WebView.VisualStateCallback>()
        val web = mockk<WebView>(relaxUnitFun = true)
        every { web.url } answers { currentUrl }
        every { web.canGoBack() } returns true
        every { web.canGoForward() } returns false
        every { web.postVisualStateCallback(0, capture(callbacks)) } returns Unit
        val session = BrowserSession().apply { view = web }
        session.load(web, BrowserPage(pageUrl, null))
        currentUrl = pageUrl
        session.showPageWhenReady(web, pageUrl)
        val request = BrowserOpenRequest(BrowserTarget.Web(nextUrl))
        session.load(web, BrowserPage(nextUrl, request))
        callbacks[0].onComplete(0)
        session.showPageWhenReady(web, pageUrl)
        session.pageFinished(web, pageUrl)
        assertEquals(1, callbacks.size)
        verify(exactly = 0) { web.evaluateJavascript(any(), any()) }
        assertTrue(session.showOpening)

        currentUrl = nextUrl
        session.showPageWhenReady(web, nextUrl)
        session.reload()
        callbacks[1].onComplete(0)
        session.showPageWhenReady(web, nextUrl)
        assertEquals(2, callbacks.size)
        assertTrue(session.showOpening)
        verify(exactly = 0) { web.visibility = View.VISIBLE }

        session.historyUpdated(web, nextUrl)
        session.showPageWhenReady(web, nextUrl)
        callbacks[2].onComplete(0)
        assertFalse(session.showOpening)
        verify(exactly = 1) { web.visibility = View.VISIBLE }
    }

    @Test fun `failed opening stays hidden until the retry can draw`() {
        val callbacks = mutableListOf<WebView.VisualStateCallback>()
        val web = mockk<WebView>(relaxed = true)
        every { web.url } returns pageUrl
        every { web.postVisualStateCallback(0, capture(callbacks)) } returns Unit
        val session = BrowserSession().apply { view = web }
        session.showPageWhenReady(web, pageUrl)
        session.pageFailed(web, "加载失败")
        callbacks[0].onComplete(0)
        session.pageFinished(web, pageUrl)
        assertEquals(1, callbacks.size)
        verify(exactly = 0) { web.visibility = View.VISIBLE }

        session.pageStarted(web, pageUrl)
        assertTrue(session.showOpening)
        session.showPageWhenReady(web, pageUrl)
        callbacks[1].onComplete(0)
        assertFalse(session.showOpening)
    }

    @Test fun `restored history and fragment changes can finish opening without a commit callback`() {
        val saved = mockk<Bundle>()
        every { saved.getStringArrayList("browser-roots") } returns null
        val item = mockk<WebHistoryItem>()
        every { item.url } returns pageUrl
        every { item.title } returns "Restored title"
        val history = mockk<WebBackForwardList>()
        every { history.currentItem } returns item
        every { history.currentIndex } returns 1
        every { history.size } returns 3
        val web = mockk<WebView>(relaxed = true)
        every { web.restoreState(saved) } returns history
        var currentUrl = pageUrl
        every { web.url } answers { currentUrl }
        val callbacks = mutableListOf<WebView.VisualStateCallback>()
        every { web.postVisualStateCallback(0, capture(callbacks)) } returns Unit
        val session = BrowserSession(saved).apply { view = web }
        assertTrue(session.restore(web))
        assertEquals("Restored title", session.title)
        assertTrue(session.canBack)
        assertTrue(session.canForward)
        verify(exactly = 0) { web.canGoBack() }
        verify(exactly = 0) { web.canGoForward() }

        session.pageFinished(web, pageUrl)
        currentUrl = "$pageUrl#section"
        session.pageFinished(web, currentUrl)
        assertEquals(2, callbacks.size)
        callbacks[0].onComplete(0)
        assertTrue(session.showOpening)
        callbacks[1].onComplete(0)
        assertFalse(session.showOpening)
        assertEquals(currentUrl, session.address)
    }

    @Test fun `chat web links open in the built-in browser`() {
        val opened = mutableListOf<BrowserTarget>()
        val external = mutableListOf<String>()
        val handler = BrowserUriHandler(opened::add, object : UriHandler {
            override fun openUri(uri: String) { external += uri }
        })
        handler.openUri("https://example.com/article?x=1#chapter")
        handler.openUri("http://localhost:8080/index.html")
        assertEquals(listOf(BrowserTarget.Web("https://example.com/article?x=1#chapter"),
            BrowserTarget.Web("http://localhost:8080/index.html")), opened)
        assertEquals(emptyList<String>(), external)
    }

    @Test fun `workspace and local HTML links open as documents`() {
        val opened = mutableListOf<BrowserTarget>()
        val handler = BrowserUriHandler(opened::add, object : UriHandler {
            override fun openUri(uri: String) { error("Unexpected external handoff: $uri") }
        })
        listOf("~/site/index.html", "/sdcard/Documents/report.HTM", "preview.xhtml").forEach(handler::openUri)
        assertEquals(listOf(BrowserTarget.Document("~/site/index.html"),
            BrowserTarget.Document("/sdcard/Documents/report.HTM"), BrowserTarget.Document("preview.xhtml")), opened)
    }

    @Test fun `file URLs retain encoded filenames query values and fragments`() {
        assertEquals(BrowserTarget.Document("/sdcard/网页/demo #1.html", "q=a%2Bb", "part%20two"),
            browserTargetFromUri("file:///sdcard/%E7%BD%91%E9%A1%B5/demo%20%231.html?q=a%2Bb#part%20two"))
        assertEquals(BrowserTarget.Document("/sdcard/demo #1.html"), browserDocument("/sdcard/demo #1.html"))
    }

    @Test fun `HTML path anchors are kept while opening the actual file`() {
        assertEquals(BrowserTarget.Document("~/site/index.html", "view=compact", "section"),
            browserDocument("~/site/index.html?view=compact#section"))
    }

    @Test fun `other apps and ordinary files retain their existing URI handling`() {
        val external = mutableListOf<String>()
        val handler = BrowserUriHandler({ error("Unexpected browser navigation: $it") }, object : UriHandler {
            override fun openUri(uri: String) { external += uri }
        })
        val links = listOf("mailto:person@example.com", "tel:12345", "market://details?id=com.example",
            "content://documents/report.html", "intent://open#Intent;scheme=example;end", "/sdcard/report.pdf")
        links.forEach(handler::openUri)
        assertEquals(links, external)
    }

    @Test fun `address entry distinguishes web hosts from workspace HTML paths`() {
        assertEquals(BrowserTarget.Web("https://example.com"), browserAddressTarget("example.com"))
        assertEquals(BrowserTarget.Web("https://example.com/site/index.html"), browserAddressTarget("example.com/site/index.html"))
        assertEquals(BrowserTarget.Web("http://localhost:8080/index.html"), browserAddressTarget("http://localhost:8080/index.html"))
        assertEquals(BrowserTarget.Document("~/site/index.html"), browserAddressTarget("~/site/index.html"))
        assertEquals(BrowserTarget.Document("./site/index.html"), browserAddressTarget("./site/index.html"))
        assertEquals(BrowserTarget.Document("index.html"), browserAddressTarget("index.html"))
        assertNull(browserAddressTarget("some search words"))
    }

    @Test fun `navigation arguments preserve local filenames and link parameters`() {
        val target = BrowserTarget.Document("/sdcard/网页 & 资料/demo #1.html", "value=a%2Bb", "part%20two")
        assertEquals(mapOf("path" to target.path, "query" to target.query, "fragment" to target.fragment),
            arguments(browserRoute(target)))
        val web = BrowserTarget.Web("https://example.com/article?name=a+b&view=full#part")
        assertEquals(mapOf("url" to web.url), arguments(browserRoute(web)))
    }

    @Test fun `agent request id travels separately from display URL arguments`() {
        val target = BrowserTarget.Web("https://example.com/?request=page&x=2")
        assertEquals(mapOf("url" to target.url, "request" to "agent-request"),
            arguments(browserRoute(target, "agent-request")))
    }

    private fun arguments(route: String): Map<String, String> = route.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
}
