package com.riffle.app.feature.reader

import android.graphics.RectF

/**
 * Page-scoped helper that turns the low-level [PdfiumTextSource] API into
 * the operations the selection state machine and overlay need:
 *
 * * [wordAtPoint] — long-press point → word range (run of non-whitespace
 *   chars). Returns null if the point doesn't hit a char.
 * * [resolveCharAt] — hit-test a point to a char index (used during drag).
 * * [quadsForRange] — multi-line selection rect computation.
 * * [extractSnippet] — selected text + ≤32 chars of leading/trailing
 *   context, for the [AnnotationSnippet] record stored on each annotation
 *   row. Matches the EPUB shape exactly.
 *
 * Pure compute over [PdfiumTextSource]; JVM-testable.
 */
class PdfTextResolver(
    private val source: PdfiumTextSource,
) {

    /** Snippet triple shared with the EPUB annotation row schema. */
    data class AnnotationSnippet(
        val highlight: String,
        val before: String,
        val after: String,
    )

    /**
     * Returns the word [CharRange] containing the char at ([x],[y]) in
     * PDF user-space coordinates, or null if no char hits within
     * [tolX]/[tolY] of the point.
     *
     * A "word" is a maximal run of non-whitespace characters. Definitions:
     * * leading boundary: start of page OR the index immediately after a
     *   whitespace char.
     * * trailing boundary: end of page OR the index of the next whitespace
     *   char.
     *
     * If the user long-presses on whitespace itself (rare; the hit-test
     * tolerance usually snaps to nearby chars), returns null and the caller
     * treats the gesture as a no-op.
     */
    fun wordAtPoint(
        pagePtr: Long,
        x: Double,
        y: Double,
        tolX: Double = 1.0,
        tolY: Double = 1.0,
    ): CharRange? {
        val hit = source.getCharIndexAtPos(pagePtr, x, y, tolX, tolY)
        if (hit < 0) return null
        val total = source.countChars(pagePtr)
        if (hit >= total) return null
        val char = source.getText(pagePtr, hit, 1)
        if (char.isEmpty() || char[0].isWhitespace()) return null

        // Walk left until we cross a whitespace boundary.
        var start = hit
        while (start > 0) {
            val prev = source.getText(pagePtr, start - 1, 1)
            if (prev.isEmpty() || prev[0].isWhitespace()) break
            start--
        }
        // Walk right until we cross a whitespace boundary.
        var end = hit + 1
        while (end < total) {
            val next = source.getText(pagePtr, end, 1)
            if (next.isEmpty() || next[0].isWhitespace()) break
            end++
        }
        return CharRange(CharIndex(start), CharIndex(end))
    }

    /**
     * Hit-test a point to a single char index, or null if nothing within
     * tolerance. Used during drag-handle repositioning where we don't want
     * to snap to whole words — the head should track the user's finger
     * exactly.
     */
    fun resolveCharAt(
        pagePtr: Long,
        x: Double,
        y: Double,
        tolX: Double = 4.0,
        tolY: Double = 4.0,
    ): CharIndex? {
        val hit = source.getCharIndexAtPos(pagePtr, x, y, tolX, tolY)
        return if (hit < 0) null else CharIndex(hit)
    }

    /**
     * Rectangles covering [range], one per visual line. PDF user-space
     * coords (Y grows upward, top > bottom). Empty for empty ranges.
     */
    fun quadsForRange(pagePtr: Long, range: CharRange): List<RectF> {
        if (range.isEmpty) return emptyList()
        return source.rectsForRange(pagePtr, range.start.value, range.length)
    }

    /**
     * Selected text + ≤ [contextChars] of leading and trailing context,
     * for cross-device verification when syncing the annotation via the
     * W3C codec. Matches EPUB's snippet field.
     */
    fun extractSnippet(
        pagePtr: Long,
        range: CharRange,
        contextChars: Int = 32,
    ): AnnotationSnippet {
        require(contextChars >= 0)
        if (range.isEmpty) return AnnotationSnippet("", "", "")

        val total = source.countChars(pagePtr)
        val highlight = source.getText(pagePtr, range.start.value, range.length)

        val beforeStart = (range.start.value - contextChars).coerceAtLeast(0)
        val beforeLen = range.start.value - beforeStart
        val before = if (beforeLen > 0) source.getText(pagePtr, beforeStart, beforeLen) else ""

        val afterStart = range.endExclusive.value
        val afterLen = (contextChars).coerceAtMost(total - afterStart).coerceAtLeast(0)
        val after = if (afterLen > 0) source.getText(pagePtr, afterStart, afterLen) else ""

        return AnnotationSnippet(highlight, before, after)
    }
}
