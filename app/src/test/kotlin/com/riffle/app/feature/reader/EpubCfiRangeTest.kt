package com.riffle.app.feature.reader

import com.riffle.core.domain.cfiDocPathToProgression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Shares the char-count model of EpubCfiTranslatorTest:
// body = "Hello world"(11) + "Second paragraph"(16) = 27 chars; body=step4, p[0]=step2, p[1]=step4, text=step1.
class EpubCfiRangeTest {

    private val simpleHtml = """
        <html>
          <head><title>Test</title></head>
          <body>
            <p>Hello world</p>
            <p>Second paragraph</p>
          </body>
        </html>
    """.trimIndent()

    // A selection wholly inside one text node factors to a shared parent with two char offsets.
    @Test
    fun `range within a single text node shares the element parent`() {
        // "Hello" = chars 0..5 of the first paragraph.
        assertEquals(
            "epubcfi(/6/4!/4/2,/1:0,/1:5)",
            buildHighlightCfiRange(spineStep = 4, html = simpleHtml, startChar = 0, endChar = 5),
        )
    }

    // A selection crossing paragraph boundaries factors to the nearest common ancestor.
    @Test
    fun `range spanning two paragraphs factors to the common ancestor`() {
        // start char 6 ('w' in "world", p[0]); end char 13 (offset 2 in "Second", p[1] starts at 11).
        assertEquals(
            "epubcfi(/6/4!/4,/2/1:6,/4/1:2)",
            buildHighlightCfiRange(spineStep = 4, html = simpleHtml, startChar = 6, endChar = 13),
        )
    }

    // The start point of the produced range round-trips back to a progression (load-bearing on reopen).
    @Test
    fun `range start round-trips through cfiDocPathToProgression`() {
        val cfi = buildHighlightCfiRange(spineStep = 4, html = simpleHtml, startChar = 0, endChar = 5)!!
        // Reconstruct the start point: take the parent + start remainder.
        val startPoint = rangeStartDocPath(cfi)!!
        assertEquals(0.0, cfiDocPathToProgression(startPoint, simpleHtml)!!, 0.0001)
    }

    @Test
    fun `returns null when the char positions fall outside the document`() {
        assertNull(buildHighlightCfiRange(spineStep = 4, html = simpleHtml, startChar = 999, endChar = 1000))
    }

    // The ViewModel-facing entry point: a selection's start progression + selected text → range CFI.
    @Test
    fun `selection at start of chapter highlighting Hello yields the same range`() {
        // progression 0.0 = char 0; "Hello" is 5 chars → end char 5.
        assertEquals(
            "epubcfi(/6/4!/4/2,/1:0,/1:5)",
            buildHighlightCfiRangeForSelection(
                spineStep = 4,
                html = simpleHtml,
                startProgression = 0.0,
                selectedText = "Hello",
            ),
        )
    }

    // On reopen a stored highlight is re-anchored from its CFI start → within-chapter progression.
    @Test
    fun `highlightStartProgression recovers the selection start from a stored range CFI`() {
        val cfi = buildHighlightCfiRangeForSelection(
            spineStep = 4, html = simpleHtml, startProgression = 0.0, selectedText = "Hello",
        )!!
        assertEquals(0.0, highlightStartProgression(cfi, simpleHtml)!!, 0.0001)
    }

    @Test
    fun `highlightStartProgression returns null for a non-range CFI`() {
        assertNull(highlightStartProgression("epubcfi(/6/4!/4/2/1:0)", simpleHtml))
    }

    @Test
    fun `readableTextBetween returns the exact readable substring across nodes`() {
        // body readable chars: "Hello world"(0..10) + "Second paragraph"(11..26)
        assertEquals("Hello", readableTextBetween(simpleHtml, 0, 5))
        assertEquals("world", readableTextBetween(simpleHtml, 6, 11))
        // spanning both paragraphs — jsoup concatenation is contiguous per readable-node walk.
        assertEquals("worldSecond", readableTextBetween(simpleHtml, 6, 17))
    }

    @Test
    fun `readableTextBetween returns null for empty or degenerate ranges`() {
        assertNull(readableTextBetween(simpleHtml, 0, 0))
        assertNull(readableTextBetween(simpleHtml, 5, 5))
        assertNull(readableTextBetween(simpleHtml, 5, 3))
    }

    @Test
    fun `readableBodyText concatenates all readable text nodes in order`() {
        assertEquals("Hello worldSecond paragraph", readableBodyText(simpleHtml))
    }

    /**
     * Regression: locateSnippetInBody uses textBefore's tail as a disambiguating anchor when the
     * snippet appears in multiple places. Without the anchor we'd fall back to first-occurrence
     * (the wrong position for a highlight that lives on a later match).
     */
    @Test
    fun `locateSnippetInBody uses textBefore anchor to pick the right occurrence`() {
        val html = """
            <html><body>
                <p>Once upon a time</p>
                <p>She said hello world and smiled</p>
                <p>He whispered hello world softly</p>
            </body></html>
        """.trimIndent()
        // readable stream: "Once upon a timeShe said hello world and smiledHe whispered hello world softly"
        val second = locateSnippetInBody(html, snippet = "hello world", before = "He whispered ")
        val body = readableBodyText(html)
        assertEquals(body.indexOf("hello world", startIndex = body.indexOf("hello world") + 1).toLong(), second)
    }

    @Test
    fun `locateSnippetInBody falls back to unique-only match when anchor is empty`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        assertEquals(0L, locateSnippetInBody(html, snippet = "Hello world", before = ""))
    }

    @Test
    fun `locateSnippetInBody returns null when snippet is ambiguous and anchor doesnt disambiguate`() {
        val html = "<html><body><p>hello world one</p><p>hello world two</p></body></html>"
        assertNull(locateSnippetInBody(html, snippet = "hello world", before = ""))
    }

    @Test
    fun `locateSnippetInBody returns null when snippet not in body`() {
        val html = "<html><body><p>Hello world</p></body></html>"
        assertNull(locateSnippetInBody(html, snippet = "goodbye", before = ""))
    }

    @Test
    fun `selection with blank selected text yields null`() {
        assertNull(
            buildHighlightCfiRangeForSelection(
                spineStep = 4,
                html = simpleHtml,
                startProgression = 0.0,
                selectedText = "",
            ),
        )
    }
}
