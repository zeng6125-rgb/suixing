package com.suixing.agent.tools

import com.suixing.agent.ui.browser.BrowserOpenRequest
import com.suixing.agent.ui.browser.BrowserOpenRequests
import com.suixing.agent.ui.browser.BrowserTarget
import com.suixing.agent.ui.browser.browserPageResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolsTest {
    @Test fun `browser tool uses the app browser for a web page and an HTML document`() = runBlocking {
        val targets = mutableListOf<BrowserTarget>()
        val page = ToolResult.success("rendered page")
        val tools = BrowserTools { target -> targets += target; page }
        val name = tools.specs.single().name

        assertEquals("browser_open", name)
        assertEquals(page, tools.execute(name, buildJsonObject { put("target", "https://example.com/page") }))
        assertEquals(page, tools.execute(name, buildJsonObject { put("target", "~/网页/demo.html?q=a#part") }))
        assertEquals(listOf(BrowserTarget.Web("https://example.com/page"),
            BrowserTarget.Document("~/网页/demo.html", "q=a", "part")), targets)
    }

    @Test fun `unsupported or malformed targets do not navigate`() = runBlocking {
        val tools = BrowserTools { error("invalid target reached the browser") }
        for (target in listOf("mailto:user@example.com", "report.pdf", "https://", "")) {
            assertFalse(tools.execute("browser_open", buildJsonObject { put("target", target) }).ok)
        }
        assertFalse(tools.execute("browser_open", buildJsonObject { put("target", JsonPrimitive(true)) }).ok)
    }

    @Test fun `one active browser request waits for its own rendered result`() = runBlocking {
        val requests = BrowserOpenRequests()
        val target = BrowserTarget.Web("https://example.com/")
        assertFalse(requests.open(target).ok)
        lateinit var active: BrowserOpenRequest
        requests.navigate = { active = it; true }
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.open(target) }
        assertFalse(first.isCompleted)
        assertEquals(active, requests.find(active.id))
        assertFalse(requests.open(target).ok)

        active.result.complete(ToolResult.success("first page"))
        assertEquals("first page", first.await().output)
        requests.navigate = { it.result.complete(ToolResult.success("second page")); true }
        assertEquals("second page", requests.open(target).output)
    }

    @Test fun `cancelling or declining a request releases the browser slot`() = runBlocking {
        val requests = BrowserOpenRequests()
        val target = BrowserTarget.Web("https://example.com/")
        requests.navigate = { false }
        assertFalse(requests.open(target).ok)
        requests.navigate = { true }
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.open(target) }
        first.cancelAndJoin()
        requests.navigate = { it.result.complete(ToolResult.success("ready")); true }
        assertTrue(requests.open(target).ok)
    }

    @Test fun `rendered page output reports truncation and preserves local address`() {
        val result = browserPageResult("/workspace/网页.html#section", "网页", JsonPrimitive("文".repeat(12_001)).toString())
        val page = Json.parseToJsonElement(result.output).jsonObject
        assertTrue(result.ok)
        assertEquals("/workspace/网页.html#section", page.str("url"))
        assertEquals("网页", page.str("title"))
        assertEquals(12_000, page.str("text")?.length)
        assertTrue(page.bool("truncated", false))
        assertFalse(browserPageResult("https://example.com/", "", "null").ok)
    }
}
