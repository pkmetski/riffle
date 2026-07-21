package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Bug 1 (2026-07-19): overlapping highlight selections must be MERGED into
 * a single annotation covering the union range, not double-annotated over the shared span. Pins
 * the char-offset range-overlap detector introduced to replace [highlightOverlapsAtSamePosition]
 * (whose substring-only test missed partial overlaps where neither snippet contains the other —
 * the exact case from the reported video).
 */
class HighlightRangeOverlapTest {

    // ---- Pure range-overlap primitive ----

    @Test
    fun charRangesOverlap_touching_isFalse() {
        // Half-open range: [0,5) and [5,10) touch at 5 but do not overlap.
        assertFalse(charRangesOverlap(CharRange2(0, 5), CharRange2(5, 10)))
    }

    @Test
    fun charRangesOverlap_disjoint_isFalse() {
        assertFalse(charRangesOverlap(CharRange2(0, 5), CharRange2(10, 20)))
    }

    @Test
    fun charRangesOverlap_partialOverlap_isTrue() {
        assertTrue(charRangesOverlap(CharRange2(0, 10), CharRange2(5, 15)))
    }

    @Test
    fun charRangesOverlap_contains_isTrue() {
        assertTrue(charRangesOverlap(CharRange2(0, 100), CharRange2(20, 30)))
        assertTrue(charRangesOverlap(CharRange2(20, 30), CharRange2(0, 100)))
    }

    // ---- computeOverlapMerge over a fixture chapter ----

    // Fixture: single paragraph, ASCII, no figures. Char offsets are trivially indexOf().
    private val html = """
        <html><body><p>There was a man and he had eight sons. Apart from that, he was nothing more than a comma on the page of History. It's sad, but that's all you can say about some people.</p></body></html>
    """.trimIndent()

    private fun annotation(
        id: String,
        textSnippet: String,
        textBefore: String,
    ): Annotation = Annotation(
        id = id,
        sourceId = "srv",
        itemId = "item",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/4/2,/1:0,/1:5)",
        color = "yellow",
        note = null,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = "",
        chapterHref = "ch1.xhtml",
        spineIndex = 0,
        progression = 0.5,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun noExisting_returnsNull() {
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "man",
            draftTextBefore = "There was a ",
            candidates = emptyList(),
        )
        assertNull(result)
    }

    @Test
    fun disjointExisting_returnsNull() {
        // Draft "man" and existing "History" — clearly disjoint.
        val existing = annotation("a", textSnippet = "History", textBefore = "on the page of ")
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "man",
            draftTextBefore = "There was a ",
            candidates = listOf(existing),
        )
        assertNull(result)
    }

    @Test
    fun partialOverlap_returnsUnion() {
        // Video repro: existing yellow "was nothing more than a comma", new selection begins inside
        // ("nothing more than a comma on the page") and extends past its tail. Neither snippet
        // contains the other — pre-fix substring detector missed this. Union covers both.
        val existing = annotation(
            id = "victim",
            textSnippet = "was nothing more than a comma",
            textBefore = "Apart from that, he ",
        )
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "nothing more than a comma on the page",
            draftTextBefore = "he was ",
            candidates = listOf(existing),
        )
        assertNotNull(result)
        assertEquals(listOf("victim"), result!!.victimIds)
        // Union start = existing start ("was nothing…"), end = draft end ("…on the page").
        val bodyText = readableBodyText(html)
        val expectedStart = bodyText.indexOf("was nothing").toLong()
        val expectedEnd = bodyText.indexOf("on the page").toLong() + "on the page".length
        assertEquals(expectedStart, result.mergedStart)
        assertEquals(expectedEnd, result.mergedEnd)
    }

    @Test
    fun draftFullyInsideExisting_returnsExistingRange() {
        // Existing highlight fully contains the new draft's range; union == existing range.
        val existing = annotation(
            id = "victim",
            textSnippet = "was nothing more than a comma on the page of History",
            textBefore = "Apart from that, he ",
        )
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "more than",
            draftTextBefore = "was nothing ",
            candidates = listOf(existing),
        )
        assertNotNull(result)
        assertEquals(listOf("victim"), result!!.victimIds)
        val bodyText = readableBodyText(html)
        assertEquals(bodyText.indexOf("was nothing").toLong(), result.mergedStart)
        assertEquals(
            bodyText.indexOf("of History").toLong() + "of History".length,
            result.mergedEnd,
        )
    }

    @Test
    fun draftCoversExisting_returnsDraftRange() {
        // New selection wraps a smaller existing highlight; union == draft range.
        val existing = annotation(
            id = "victim",
            textSnippet = "comma",
            textBefore = "more than a ",
        )
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "than a comma on the page",
            draftTextBefore = "was nothing more ",
            candidates = listOf(existing),
        )
        assertNotNull(result)
        assertEquals(listOf("victim"), result!!.victimIds)
        val bodyText = readableBodyText(html)
        assertEquals(bodyText.indexOf("than a comma").toLong(), result.mergedStart)
        assertEquals(
            bodyText.indexOf("on the page").toLong() + "on the page".length,
            result.mergedEnd,
        )
    }

    @Test
    fun multipleExistingUnioned() {
        // Two adjacent-but-non-touching existing highlights both overlap a big draft; the union
        // spans the leftmost start through the rightmost end.
        val a = annotation(
            id = "left",
            textSnippet = "was nothing more",
            textBefore = "Apart from that, he ",
        )
        val b = annotation(
            id = "right",
            textSnippet = "page of History",
            textBefore = "comma on the ",
        )
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "more than a comma on the page",
            draftTextBefore = "was nothing ",
            candidates = listOf(a, b),
        )
        assertNotNull(result)
        assertEquals(setOf("left", "right"), result!!.victimIds.toSet())
        val bodyText = readableBodyText(html)
        assertEquals(bodyText.indexOf("was nothing").toLong(), result.mergedStart)
        assertEquals(
            bodyText.indexOf("page of History").toLong() + "page of History".length,
            result.mergedEnd,
        )
    }

    @Test
    fun touchingExisting_notMerged() {
        // Adjacent (touching) highlights are handled by the edit-time merge path, not the create-
        // time overlap merger. Ensure we don't merge them here — that would surprise users who
        // deliberately created two separate same-color neighbours (2026-07-05 design intent).
        val existing = annotation(
            id = "neighbor",
            textSnippet = "eight sons.",
            textBefore = "he had ",
        )
        // Draft immediately after existing: " Apart from" starts exactly where existing ends.
        val result = computeOverlapMerge(
            html = html,
            draftSnippet = "Apart from",
            draftTextBefore = "eight sons. ",
            candidates = listOf(existing),
        )
        assertNull(result)
    }

    // ---- buildMergedDraftFields — DOM rebuild after union ----

    // ---- Multi-paragraph snippet locates across block boundaries (Bug B, 2026-07-19) ----

    /**
     * `readableBodyText` concatenates adjacent block-element text nodes with NO separator (blank-
     * only text nodes between `<p>` elements are skipped). A user selection spanning a paragraph
     * boundary has "\n" (or a space) in `textSnippet` that isn't in the body stream — verbatim
     * `indexOf` fails and the pre-fix overlap detector treated overlapping multi-paragraph
     * selections as disjoint. Whitespace-tolerant fallback pins this: the snippet still locates.
     */
    private val multiParaHtml = """
        <html><body>
            <p>The first sentence in paragraph one.</p>
            <p>The second sentence in paragraph two.</p>
        </body></html>
    """.trimIndent()

    /**
     * The user's video (2026-07-20): a multi-paragraph selection painted a wash spanning the
     * WHOLE second paragraph instead of just the selected part. Root cause: end-char computed as
     * `startChar + snippet.length` overshot by the newline count (the `\n` in the snippet has no
     * counterpart in the readable body stream). Pin the helper that walks non-whitespace chars.
     */
    @Test
    fun snippetEndCharInBody_multiParagraph_doesNotOvershoot() {
        val body = readableBodyText(multiParaHtml)
        val snippet = "paragraph one.\nThe second"
        val start = body.indexOf("paragraph one.").toLong()
        val end = snippetEndCharInBody(multiParaHtml, start, snippet)
        // Expected end: position of "second" + length of "second" = just past "second"
        val expectedEnd = body.indexOf("The second").toLong() + "The second".length
        assertEquals(expectedEnd, end)
        // If the pre-fix `start + snippet.length` were used, end would be `start + 25` which
        // extends 1 char past "second" (into " sentence") because the body drops the newline.
        val naiveEnd = start + snippet.length
        assertTrue(
            "naive .length end (${naiveEnd}) must overshoot the corrected end (${end})",
            naiveEnd > end,
        )
    }

    @Test
    fun snippetEndCharInBody_singleParagraph_matchesLength() {
        val html = "<html><body><p>hello world</p></body></html>"
        val body = readableBodyText(html)
        val start = body.indexOf("hello").toLong()
        val end = snippetEndCharInBody(html, start, "hello world")
        assertEquals(start + "hello world".length, end)
    }

    @Test
    fun multiParagraphSnippet_locatesAcrossBlockBoundary() {
        // Snippet contains "\n" between paragraphs; body has no separator.
        val snippet = "paragraph one.\nThe second sentence"
        val start = locateSnippetInBody(multiParaHtml, snippet, "in ")
        assertNotNull(start)
        val body = readableBodyText(multiParaHtml)
        // The match should start at "paragraph one." in the body.
        assertEquals(body.indexOf("paragraph one.").toLong(), start!!)
    }

    @Test
    fun multiParagraphOverlap_detectedAndMerged() {
        // Existing highlight ends mid-para-2. New selection starts inside para-1 and extends
        // past existing's tail — pre-fix (substring test on "paragraph one.\nThe second") failed
        // because the snippet's newline isn't in the body stream.
        val existing = annotation(
            id = "cross-para",
            textSnippet = "sentence in paragraph one.\nThe second",
            textBefore = "The first ",
        )
        val result = computeOverlapMerge(
            html = multiParaHtml,
            draftSnippet = "in paragraph one.\nThe second sentence",
            draftTextBefore = "sentence ",
            candidates = listOf(existing),
        )
        assertNotNull(result)
        assertEquals(listOf("cross-para"), result!!.victimIds)
    }

    @Test
    fun buildMergedDraftFields_producesMergedSnippet() {
        val draftSnippet = "nothing more than a comma on the page"
        val draftTextBefore = "he was "
        val victim = annotation(
            id = "v",
            textSnippet = "was nothing more than a comma",
            textBefore = "Apart from that, he ",
        )
        val overlap = computeOverlapMerge(html, draftSnippet, draftTextBefore, listOf(victim))!!
        val merged = buildMergedDraftFields(
            html = html,
            draftSpineIndex = 0,
            draftEmbeddedFigures = null,
            overlap = overlap,
            candidates = listOf(victim),
        )
        assertNotNull(merged)
        assertEquals("was nothing more than a comma on the page", merged!!.textSnippet)
        assertTrue(merged.cfiRange.startsWith("epubcfi("))
        assertTrue(merged.textBefore.endsWith("that, he "))
        assertTrue(merged.textAfter.startsWith(" of History"))
    }
}
