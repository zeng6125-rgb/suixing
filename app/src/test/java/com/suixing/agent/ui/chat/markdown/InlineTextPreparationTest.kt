@file:Suppress("UNCHECKED_CAST")

package com.suixing.agent.ui.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import io.mockk.ConstantMatcher
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineTextPreparationTest {
    @Test fun shortSettledLinesRenderSynchronouslyOnFirstComposition() = runBlocking {
        withTimeout(15_000) {
            for (source in listOf(
                "Plain thinking line with no markup",
                "Short **formatted** reasoning with `code` and /sdcard/a.txt",
            )) {
                val harness = Harness(this, source, observeScanner = false)
                try {
                    harness.start()
                    assertEquals(harness.reference(source).text, harness.output?.text)
                    assertEquals(harness.reference(source).spanStyles, harness.output?.spanStyles)
                } finally { harness.close() }
            }
        }
    }

    @Test fun aColdFormattedParagraphDoesNotScanOnTheCompositionThread() = runBlocking {
        withTimeout(15_000) {
            val source = "cold **formatted paragraph** " + "read [docs](https://example.test) ".repeat(80)
            val harness = Harness(this, source)
            try {
                harness.start()
                assertEquals("InlineText must consume prepared inline output during composition", 0,
                    harness.compositionScans.get())
                harness.await(harness.reference(source))
            } finally { harness.close() }
        }
    }

    @Test fun everyFormattedPrefixAndAReplacementKeepTextStylesLinksAndPathCallbacks() = runBlocking {
        withTimeout(15_000) {
            val harness = Harness(this, "", observeScanner = false)
            try {
                harness.start()
                for (source in listOf(
                    "~~*a **b** c*~~ raw \\*literal\\* `x`",
                    "**[a\\*b](https://example.test/a(b))** /sdcard/中文.txt",
                    "emoji 😅 **word**\r\n[unfinished nested [label",
                )) {
                    for (end in 0..source.length) {
                        val prefix = source.substring(0, end)
                        harness.source.value = prefix
                        harness.await(harness.reference(prefix))
                    }
                }
                val replacement = "read /sdcard/next.txt and [docs](https://next.test)."
                harness.source.value = replacement
                harness.await(harness.reference(replacement))
                val path = harness.output!!.getLinkAnnotations(0, harness.output!!.length)
                    .map { it.item }.filterIsInstance<LinkAnnotation.Clickable>().single()
                path.linkInteractionListener!!.onClick(path)
                assertEquals(listOf("/sdcard/next.txt"), harness.clickedPaths)
            } finally { harness.close() }
        }
    }

    @Test fun longFormattedParagraphAppendsPreserveTheCompleteOutput() = runBlocking {
        withTimeout(30_000) {
            val unit = "**bold** text [docs](https://example.test/a) `code` and more text. "
            val whole = unit.repeat(131_072 / unit.length + 1).take(131_072)
            val harness = Harness(this, "", observeScanner = false)
            try {
                harness.start()
                for (size in listOf(2_048, 8_192, 32_768, 131_072)) {
                    val prefix = whole.take(size)
                    harness.source.value = prefix
                    harness.await(harness.reference(prefix))
                }
            } finally { harness.close() }
        }
    }

    private class Harness(scope: CoroutineScope, initial: String, private val observeScanner: Boolean = true) {
        val source = mutableStateOf(initial)
        var output: AnnotatedString? = null
            private set
        val clickedPaths = mutableListOf<String>()
        val compositionScans = AtomicInteger()
        private val composingThread = Thread.currentThread()
        private var composing = false
        private val referenceMode = ThreadLocal<Boolean>()
        private val pathClick: (String) -> Unit = { clickedPaths += it }
        private var codeBackground = Color.Unspecified
        private var codeColor = Color.Unspecified
        private var linkColor = Color.Unspecified
        private var codeFontSize = TextUnit.Unspecified
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        fun start() {
            mockkStatic(TEXT_OWNER)
            for (method in Class.forName(TEXT_OWNER).methods.filter {
                it.parameterTypes.firstOrNull() == AnnotatedString::class.java &&
                    Composer::class.java in it.parameterTypes && !it.isSynthetic
            }) {
                every { method.invoke(null, *method.parameterTypes.map {
                    match(ConstantMatcher<Any>(true), it.kotlin as KClass<Any>)
                }.toTypedArray()) } answers {
                    output = invocation.args.first() as AnnotatedString
                    Unit
                }
            }
            if (observeScanner) {
                mockkStatic(MARKDOWN_OWNER)
                val scan = Class.forName(MARKDOWN_OWNER).methods.single {
                    it.name.startsWith("scanInlineMarkdown-") && '$' !in it.name
                }
                every { scan.invoke(null, *scan.parameterTypes.map {
                    match(ConstantMatcher<Any>(true), it.kotlin as KClass<Any>)
                }.toTypedArray()) } answers {
                    if (composing && Thread.currentThread() === composingThread) compositionScans.incrementAndGet()
                    callOriginal()
                }
            }
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (referenceMode.get() == true) null else callOriginal()
            }
            composition.setContent {
                val text = source.value
                val scheme = MaterialTheme.colorScheme
                codeBackground = scheme.surfaceContainerHigh.copy(alpha = 0.6f)
                codeColor = scheme.onSurfaceVariant
                linkColor = scheme.primary
                codeFontSize = MaterialTheme.typography.bodySmall.fontSize
                CompositionLocalProvider(LocalPathClick provides pathClick) {
                    composing = true
                    MarkdownBlock(MdBlock.Paragraph(text), TextStyle.Default, Color.Black)
                    composing = false
                }
            }
        }

        fun reference(text: String): AnnotatedString {
            referenceMode.set(true)
            return try {
                scanInlineMarkdown(text, codeBackground, codeColor, linkColor,
                    onPathClick = pathClick, codeFontSize = codeFontSize)
            } finally { referenceMode.remove() }
        }

        suspend fun await(expected: AnnotatedString) {
            try {
                while (!equivalent(expected, output)) {
                    Snapshot.sendApplyNotifications()
                    repeat(8) { yield() }
                    nanos += 16_000_000L
                    clock.sendFrame(nanos)
                    Snapshot.sendApplyNotifications()
                    repeat(8) { yield() }
                }
            } catch (error: TimeoutCancellationException) {
                val actual = output
                throw AssertionError(
                    "InlineText input=${source.value.take(120)} (${source.value.length} chars), " +
                        "expected=${expected.text.take(120)} (${expected.length} chars), " +
                        "actual=${actual?.text?.take(120)} (${actual?.length} chars), " +
                        "spans=${expected.spanStyles.size}/${actual?.spanStyles?.size}, " +
                        "links=${links(expected).take(3)}/${actual?.let(::links)?.take(3)}",
                    error,
                )
            }
        }

        suspend fun close() = withContext(NonCancellable) {
            composing = false
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            unmockkObject(NativeMarkdown)
            if (observeScanner) unmockkStatic(MARKDOWN_OWNER)
            unmockkStatic(TEXT_OWNER)
        }

        private fun equivalent(expected: AnnotatedString, actual: AnnotatedString?): Boolean =
            actual != null && expected.text == actual.text && expected.spanStyles == actual.spanStyles &&
                links(expected) == links(actual)

        private fun links(text: AnnotatedString): List<String> = text.getLinkAnnotations(0, text.length).map {
            val target = when (val link = it.item) {
                is LinkAnnotation.Url -> "url:${link.url}"
                is LinkAnnotation.Clickable -> "path:${link.tag}"
                else -> link.toString()
            }
            "${it.start}:${it.end}:$target"
        }

        companion object {
            private const val TEXT_OWNER = "androidx.compose.material3.TextKt"
            private const val MARKDOWN_OWNER = "com.suixing.agent.ui.chat.markdown.MarkdownTextKt"
        }
    }
}
