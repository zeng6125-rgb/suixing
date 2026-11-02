package com.suixing.agent.ui.chat.markdown

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Parsed documents owned by the rows about to mount, rather than an extra process cache. */
internal val LocalPreparedMarkdown = staticCompositionLocalOf<Map<String, List<MdBlock>>> { emptyMap() }

/**
 * A small LRU for work done ahead of row mounting. It deliberately has its own lower budget than
 * [SettledBlocks]: a prepared result is also held by the row window, and the settled cache serves
 * the longer-lived scroll-back case. Oversize documents are still returned to the caller but are
 * not retained here.
 */
internal class PreparedMarkdownCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxSourceChars: Int = DEFAULT_MAX_SOURCE_CHARS,
    private val maxEstimatedBytes: Long = DEFAULT_MAX_ESTIMATED_BYTES,
) {
    private data class Entry(val blocks: List<MdBlock>, val estimatedBytes: Long)

    private sealed interface Lookup {
        data class Hit(val blocks: List<MdBlock>) : Lookup
        data class Wait(val completion: CompletableDeferred<List<MdBlock>>) : Lookup
        data class Compute(val completion: CompletableDeferred<List<MdBlock>>) : Lookup
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val inFlight = HashMap<String, CompletableDeferred<List<MdBlock>>>()
    private var sourceChars = 0
    private var estimatedBytes = 0L

    init {
        require(maxEntries > 0)
        require(maxSourceChars > 0)
        require(maxEstimatedBytes > 0)
    }

    /** Share an active parse for this exact source string; the parser always runs outside [lock]. */
    internal suspend fun getOrParse(text: String, parse: suspend () -> List<MdBlock>): List<MdBlock> {
        while (true) {
            val context = currentCoroutineContext()
            context.ensureActive()
            val lookup = synchronized(lock) {
                entries[text]?.let { return@synchronized Lookup.Hit(it.blocks) }
                inFlight[text]?.let { return@synchronized Lookup.Wait(it) }
                val completion = CompletableDeferred<List<MdBlock>>()
                inFlight[text] = completion
                Lookup.Compute(completion)
            }

            when (lookup) {
                is Lookup.Hit -> return lookup.blocks
                is Lookup.Wait -> {
                    try {
                        return lookup.completion.await()
                    } catch (cancelled: CancellationException) {
                        // A waiter cancelled by its own caller exits. If the owner was cancelled,
                        // this coroutine is still active and can claim the now-unowned parse.
                        context.ensureActive()
                    }
                }
                is Lookup.Compute -> {
                    try {
                        context.ensureActive()
                        val blocks = parse()
                        context.ensureActive()
                        val weight = estimateMarkdownCacheBytes(text, blocks)
                        synchronized(lock) {
                            context.ensureActive()
                            if (inFlight[text] === lookup.completion) {
                                retainIfWithinBudget(text, blocks, weight)
                            }
                        }
                        lookup.completion.complete(blocks)
                        removeInFlightIfOwned(text, lookup.completion)
                        return blocks
                    } catch (cancelled: CancellationException) {
                        removeInFlightIfOwned(text, lookup.completion)
                        lookup.completion.completeExceptionally(cancelled)
                        throw cancelled
                    } catch (failure: Throwable) {
                        removeInFlightIfOwned(text, lookup.completion)
                        lookup.completion.completeExceptionally(failure)
                        throw failure
                    }
                }
            }
        }
    }

    /** Drop a retained parse after another cache becomes the preferred owner of the same key. */
    internal fun remove(text: String) {
        synchronized(lock) {
            entries.remove(text)?.let { entry ->
                sourceChars -= text.length
                estimatedBytes -= entry.estimatedBytes
            }
        }
    }

    private fun removeInFlightIfOwned(text: String, completion: CompletableDeferred<List<MdBlock>>) {
        synchronized(lock) {
            if (inFlight[text] === completion) inFlight.remove(text)
        }
    }

    /** Called only under [lock]. */
    private fun retainIfWithinBudget(text: String, blocks: List<MdBlock>, weight: Long) {
        if (text.length > maxSourceChars || weight > maxEstimatedBytes) return
        entries.remove(text)?.let { previous ->
            sourceChars -= text.length
            estimatedBytes -= previous.estimatedBytes
        }
        entries[text] = Entry(blocks, weight)
        sourceChars += text.length
        estimatedBytes += weight
        while (entries.size > maxEntries || sourceChars > maxSourceChars || estimatedBytes > maxEstimatedBytes) {
            val oldest = entries.entries.iterator()
            val entry = oldest.next()
            sourceChars -= entry.key.length
            estimatedBytes -= entry.value.estimatedBytes
            oldest.remove()
        }
    }

    internal fun sizeForTest(): Int = synchronized(lock) { entries.size }
    internal fun estimatedBytesForTest(): Long = synchronized(lock) { estimatedBytes }
    internal fun maxEstimatedBytesForTest(): Long = maxEstimatedBytes

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val DEFAULT_MAX_SOURCE_CHARS = 128 * 1024
        const val DEFAULT_MAX_ESTIMATED_BYTES = 512L * 1024
    }
}

// These are settled historical rows in a small viewport window. Live rows are filtered out by
// ChatScreen before it calls this function, so streaming prefixes never enter the preparse LRU.
private val preparedMarkdownCache = PreparedMarkdownCache()

internal suspend fun prepareMarkdownTexts(texts: List<String>): Map<String, List<MdBlock>> {
    val required = texts.filter { it.length >= SettledBlocks.MIN_CHARS }.distinct()
    if (required.isEmpty()) return emptyMap()
    return withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        buildMap {
            for (text in required) {
                context.ensureActive()
                val settled = SettledBlocks.get(text)
                val blocks = if (settled != null) {
                    preparedMarkdownCache.remove(text)
                    settled
                } else {
                    preparedMarkdownCache.getOrParse(text) {
                        context.ensureActive()
                        parseMarkdown(text) { context.ensureActive() }
                    }
                }
                put(text, blocks)
            }
        }
    }
}
