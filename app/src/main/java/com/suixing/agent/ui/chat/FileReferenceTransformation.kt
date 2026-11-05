package com.suixing.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Compact labels are visual only: selection, drafts, clipboard and sends use the original paths. */
internal class FileReferenceTransformation(
    private val color: Color,
    private val commandEnd: Int? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (commandEnd == null && '@' !in text.text) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val segments = ArrayList<ReferenceOffsetSegment>()
        var source = 0
        val displayed = buildAnnotatedString {
            for (reference in fileReferences(text.text)) {
                if (source < reference.start) {
                    val before = length
                    append(text.subSequence(source, reference.start))
                    segments += ReferenceOffsetSegment(source, reference.start, before, length)
                }
                val before = length
                append(fileReferenceLabel(reference.path))
                addStyle(SpanStyle(color = color), before, length)
                segments += ReferenceOffsetSegment(reference.start, reference.end, before, length)
                source = reference.end
            }
            if (source < text.length) {
                val before = length
                append(text.subSequence(source, text.length))
                segments += ReferenceOffsetSegment(source, text.length, before, length)
            }
        }
        val offsets = ReferenceOffsetMapping(segments, text.length, displayed.length)
        val styled = if (commandEnd == null) displayed else buildAnnotatedString {
            append(displayed)
            addStyle(SpanStyle(color = color), 0, offsets.originalToTransformed(commandEnd))
        }
        return TransformedText(styled, offsets)
    }
}

private data class ReferenceOffsetSegment(
    val originalStart: Int,
    val originalEnd: Int,
    val displayedStart: Int,
    val displayedEnd: Int,
)

private class ReferenceOffsetMapping(
    private val segments: List<ReferenceOffsetSegment>,
    private val originalLength: Int,
    private val displayedLength: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val at = offset.coerceIn(0, originalLength)
        val segment = segments.firstOrNull { at <= it.originalEnd } ?: return displayedLength
        return segment.displayedStart + scale(
            at - segment.originalStart,
            segment.originalEnd - segment.originalStart,
            segment.displayedEnd - segment.displayedStart,
        )
    }

    override fun transformedToOriginal(offset: Int): Int {
        val at = offset.coerceIn(0, displayedLength)
        val segment = segments.firstOrNull { at <= it.displayedEnd } ?: return originalLength
        return segment.originalStart + scale(
            at - segment.displayedStart,
            segment.displayedEnd - segment.displayedStart,
            segment.originalEnd - segment.originalStart,
        )
    }

    private fun scale(offset: Int, from: Int, to: Int): Int =
        if (from == 0) 0 else (offset.toLong() * to / from).toInt()
}
