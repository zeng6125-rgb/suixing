package com.suixing.agent.ui.browser

import java.io.File
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserLocalFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `HTML files load from the clicked directory with query and anchor intact`() {
        val directory = temporary.newFolder("site")
        val html = File(directory, "网页 #1.html").apply { writeText("<h1>本地页面</h1>") }
        val files = BrowserLocalFiles()
        val url = files.open(BrowserTarget.Document(html.path, "value=a%2Bb", "section"), html)
        assertEquals(html.canonicalFile, files.fileForUrl(url))
        assertEquals("value=a%2Bb", URI(url).rawQuery)
        assertEquals("section", URI(url).rawFragment)
        assertEquals("${html.canonicalPath}?value=a%2Bb#section", files.displayUrl(url))
    }

    @Test fun `relative CSS modules JSON and images stay in the local page origin`() {
        val directory = temporary.newFolder("site")
        val html = File(directory, "index.html").apply {
            writeText("<link rel=stylesheet href=css/site.css><script type=module src=js/app.mjs></script>")
        }
        File(directory, "css").mkdir()
        File(directory, "js").mkdir()
        val css = File(directory, "css/site.css").apply { writeText("body { color: green; }") }
        val script = File(directory, "js/app.mjs").apply { writeText("fetch('/data.json').then(r => r.json())") }
        val json = File(directory, "data.json").apply { writeText("{\"value\":42}") }
        val image = File(directory, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val files = BrowserLocalFiles()
        val page = URI(files.open(BrowserTarget.Document(html.path), html))
        assertEquals(css.readText(), files.fileForUrl(page.resolve("css/site.css").toString())!!.readText())
        assertEquals(script.readText(), files.fileForUrl(page.resolve("js/app.mjs").toString())!!.readText())
        assertEquals(json.readText(), files.fileForUrl(page.resolve("/data.json").toString())!!.readText())
        assertTrue(image.readBytes().contentEquals(files.fileForUrl(page.resolve("image.png").toString())!!.readBytes()))
        assertEquals("text/css", browserMimeType(css.name))
        assertEquals("application/javascript", browserMimeType(script.name))
        assertEquals("application/json", browserMimeType(json.name))
    }

    @Test fun `different local sites keep independent origins and earlier history remains readable`() {
        val first = File(temporary.newFolder("first"), "index.html").apply { writeText("first") }
        val second = File(temporary.newFolder("second"), "index.html").apply { writeText("second") }
        val files = BrowserLocalFiles()
        val firstUrl = files.open(BrowserTarget.Document(first.path), first)
        val secondUrl = files.open(BrowserTarget.Document(second.path), second)
        assertNotEquals(URI(firstUrl).host, URI(secondUrl).host)
        assertEquals("first", files.fileForUrl(firstUrl)!!.readText())
        assertEquals("second", files.fileForUrl(secondUrl)!!.readText())
        assertEquals(firstUrl, files.open(BrowserTarget.Document(first.path), first))
    }

    @Test fun `restoring the browser retains local origins used by WebView history`() {
        val first = File(temporary.newFolder("first"), "index.html").apply { writeText("first") }
        val second = File(temporary.newFolder("second"), "index.html").apply { writeText("second") }
        val files = BrowserLocalFiles()
        val firstUrl = files.open(BrowserTarget.Document(first.path), first)
        val secondUrl = files.open(BrowserTarget.Document(second.path), second)
        val restored = BrowserLocalFiles(files.savedRoots())
        assertEquals(first.canonicalFile, restored.fileForUrl(firstUrl))
        assertEquals(second.canonicalFile, restored.fileForUrl(secondUrl))
        assertEquals(secondUrl, restored.open(BrowserTarget.Document(second.path), second))
    }

    @Test fun `opening a different local site in a fresh browser does not reuse another sites origin`() {
        val first = File(temporary.newFolder("first"), "index.html").apply { writeText("first") }
        val second = File(temporary.newFolder("second"), "index.html").apply { writeText("second") }
        val firstUrl = BrowserLocalFiles().open(BrowserTarget.Document(first.path), first)
        val secondUrl = BrowserLocalFiles().open(BrowserTarget.Document(second.path), second)
        assertNotEquals(URI(firstUrl).host, URI(secondUrl).host)
        assertEquals(firstUrl, BrowserLocalFiles().open(BrowserTarget.Document(first.path), first))
    }

    @Test fun `local requests cannot read beyond an opened directory`() {
        val directory = temporary.newFolder("site")
        val html = File(directory, "index.html").apply { writeText("page") }
        val outside = temporary.newFile("outside.txt").apply { writeText("outside") }
        val files = BrowserLocalFiles()
        val origin = URI(files.open(BrowserTarget.Document(html.path), html))
        assertTrue(outside.exists())
        assertNull(files.fileForUrl("https://${origin.host}/../outside.txt"))
        assertNull(files.fileForUrl("https://${origin.host}/%2e%2e/outside.txt"))
        assertNull(files.fileForUrl("https://${origin.host}/missing.css"))
    }

    @Test fun `remote pages and unsupported schemes are not mapped to local files`() {
        val html = temporary.newFile("index.html").apply { writeText("page") }
        val files = BrowserLocalFiles()
        val url = files.open(BrowserTarget.Document(html.path), html)
        assertNull(files.fileForUrl("https://example.com/index.html"))
        assertNull(files.fileForUrl(url.replace("https://", "http://")))
        assertNull(files.response("https://example.com/index.html"))
        val remoteWithLocalUrl = "https://example.com/index.html?link=$url"
        assertNull(files.response(remoteWithLocalUrl))
        assertEquals(remoteWithLocalUrl, files.displayUrl(remoteWithLocalUrl))
    }

    @Test fun `a missing HTML file fails without creating a local site`() {
        val missing = File(temporary.root, "missing.html")
        val files = BrowserLocalFiles()
        assertFalse(missing.exists())
        try {
            files.open(BrowserTarget.Document(missing.path), missing)
            throw AssertionError("A missing document must not become a browser page")
        } catch (_: IllegalArgumentException) {
            assertEquals(emptyList<String>(), files.savedRoots())
        }
    }
}
