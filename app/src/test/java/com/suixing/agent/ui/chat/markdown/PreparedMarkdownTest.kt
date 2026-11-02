package com.suixing.agent.ui.chat.markdown

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedMarkdownTest {
    @Test fun `prepared long documents equal the public parser without filling the global cache`() = runBlocking {
        val texts = listOf(
            ("prepared paragraph\n\n- first\n  - nested\n\n").repeat(30),
            "# prepared heading\r\n\r\n```kotlin\r\n" + "val item = 1\r\n".repeat(80) + "```",
            "| a | b |\n| --- | --- |\n" + "| left | right |\n".repeat(80),
        )
        val cacheSize = SettledBlocks.sizeForTest()
        val result = prepareMarkdownTexts(texts + "short")
        assertEquals(texts.toSet(), result.keys)
        for (text in texts) assertEquals(parseMarkdown(text), result[text])
        assertEquals(cacheSize, SettledBlocks.sizeForTest())
    }

    @Test fun `short text does not enqueue background preparation`() = runBlocking {
        assertTrue(prepareMarkdownTexts(listOf("small", "")).isEmpty())
    }

    @Test fun `prepared cache reuses parses and evicts least recently used entry`() = runBlocking {
        val cache = PreparedMarkdownCache(maxEntries = 2, maxEstimatedBytes = 64 * 1024)
        val texts = listOf("first", "second", "third").map { "$it " + "body ".repeat(100) }
        val calls = AtomicInteger()
        suspend fun parse(text: String) = cache.getOrParse(text) {
            calls.incrementAndGet()
            parseMarkdown(text)
        }

        val first = parse(texts[0])
        assertSame(first, parse(texts[0]))
        parse(texts[1])
        assertSame(first, parse(texts[0])) // refresh first before inserting third
        parse(texts[2])
        assertEquals(3, calls.get())
        parse(texts[0])
        assertEquals("the least recently used document should be reparsed", 3, calls.get())
        parse(texts[1])
        assertEquals(4, calls.get())
        assertTrue(cache.sizeForTest() <= 2)
        assertTrue(cache.estimatedBytesForTest() <= cache.maxEstimatedBytesForTest())
    }

    @Test fun `prepared cache returns but does not retain a document over its byte budget`() = runBlocking {
        val cache = PreparedMarkdownCache(maxEntries = 4, maxEstimatedBytes = 64)
        val text = "budget " + "a long paragraph ".repeat(100)
        val calls = AtomicInteger()
        repeat(2) {
            cache.getOrParse(text) {
                calls.incrementAndGet()
                parseMarkdown(text)
            }
        }
        assertEquals("over-budget documents remain usable but cannot stay cached", 2, calls.get())
        assertEquals(0, cache.sizeForTest())
        assertEquals(0L, cache.estimatedBytesForTest())
    }

    @Test fun `concurrent preparation shares one parse`() = runBlocking {
        val cache = PreparedMarkdownCache()
        val text = "shared " + "body ".repeat(100)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger()
        val start = CompletableDeferred<Unit>()
        val blocks = coroutineScope {
            val requests = (0 until 8).map {
                async(Dispatchers.Default) {
                    if (readyCount.incrementAndGet() == 8) ready.complete(Unit)
                    start.await()
                    cache.getOrParse(text) {
                        calls.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        parseMarkdown(text)
                    }
                }
            }
            // All requests are waiting at the same gate before the single parse is released.
            withTimeout(2_000) { ready.await() }
            start.complete(Unit)
            entered.await()
            kotlinx.coroutines.yield()
            release.complete(Unit)
            requests.awaitAll()
        }
        assertEquals(1, calls.get())
        assertTrue(blocks.all { it === blocks.first() })
    }

    @Test fun `waiters retry when the parse owner is cancelled`() = runBlocking {
        val cache = PreparedMarkdownCache()
        val text = "cancelled owner " + "body ".repeat(100)
        val entered = CompletableDeferred<Unit>()
        val waiterStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val owner = async(Dispatchers.Default) {
            cache.getOrParse(text) {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        entered.await()
        val waiter = async(Dispatchers.Default) {
            waiterStarted.complete(Unit)
            cache.getOrParse(text) {
                calls.incrementAndGet()
                parseMarkdown(text)
            }
        }
        waiterStarted.await()
        owner.cancelAndJoin()
        val result = withTimeout(2_000) { waiter.await() }
        assertEquals(parseMarkdown(text), result)
        assertEquals(1, calls.get())
    }

    @Test(expected = CancellationException::class)
    fun `long code parsing checks cancellation within the block`() {
        var checks = 0
        parseMarkdown("```\n" + "line\n".repeat(1_000) + "```") {
            if (++checks == 10) throw CancellationException("replaced chat")
        }
    }

    @Test(expected = CancellationException::class)
    fun `long table parsing checks cancellation within the block`() {
        var checks = 0
        parseMarkdown("| a | b |\n| --- | --- |\n" + "| a | b |\n".repeat(1_000)) {
            if (++checks == 10) throw CancellationException("replaced chat")
        }
    }

    @Test(expected = CancellationException::class)
    fun `nested list parsing checks cancellation within child items`() {
        var checks = 0
        parseMarkdown("- parent\n" + "  - child\n".repeat(1_000)) {
            if (++checks == 10) throw CancellationException("replaced chat")
        }
    }
}
