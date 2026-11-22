package com.suixing.agent.ui.browser

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.platform.UriHandler
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineStart
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
    @Test fun `initial WebView load waits for the browser shell to draw and yield its frame`() = runBlocking {
        withTimeout(1_000) {
            val clock = BroadcastFrameClock()
            val session = BrowserSession()
            val opening = launch(clock, start = CoroutineStart.UNDISPATCHED) { session.awaitOpeningFrame() }
            clock.sendFrame(1L)
            assertFalse(opening.isCompleted)
            session.openingDrawn.complete(Unit)
            yield()
            assertFalse(opening.isCompleted)
            clock.sendFrame(2L)
            opening.join()
        }
    }

    @Test fun `opening feedback stays until WebView can draw the main page`() {
        val session = BrowserSession()
        assertTrue(session.showOpening)
        session.progress = 80
        assertTrue(session.showOpening)
        session.progress = 100
        assertTrue(session.showOpening)
        session.pageCommitted = true
        assertFalse(session.showOpening)
    }

    @Test fun `an opening error replaces the loading feedback`() {
        val session = BrowserSession()
        session.error = "网页加载失败"
        assertFalse(session.showOpening)
        session.error = null
        assertTrue(session.showOpening)
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
