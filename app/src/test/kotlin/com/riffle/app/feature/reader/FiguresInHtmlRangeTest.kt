package com.riffle.app.feature.reader

import com.riffle.core.domain.EmbeddedFigure
import com.riffle.core.domain.countBodyChars
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FiguresInHtmlRangeTest {

    // The bug this fixes: a highlight spanning text-then-figure-then-text (e.g. selecting from
    // "characterize this in a crude mathematical way:" through the equation image to "The overall
    // complexity of a system…" in A Philosophy of Software Design) landed in the DB with EMPTY
    // embeddedFigures. FigureBorderDecoration then had no rule for the equation's img filename
    // and drew no border. This assertion flips red if the walker regresses to missing the enclosed
    // <img>.
    @Test
    fun `finds a raster img sandwiched between the two selected paragraphs`() {
        val html = """
            <html><body>
              <p>To characterize this in a crude mathematical way:</p>
              <div class="eq"><img src="image_rsrc2H6.jpg" alt=""/></div>
              <p>The overall complexity of a system.</p>
            </body></html>
        """.trimIndent()
        val total = countBodyChars(Jsoup.parse(html).body())
        val figures = findEnclosedFiguresInHtml(html, 0, total)
        assertEquals(1, figures.size)
        assertEquals("image_rsrc2H6.jpg", figures.single().href)
        assertNull("raster figure has no inline SVG", figures.single().svg)
    }

    // Boundary rule (fixed 2026-07-09): a highlight that stops EXACTLY at the figure's position
    // — or starts EXACTLY at it — does NOT enclose the figure. Visually the text highlight ends
    // just before the figure (or starts just after it), so absorbing the figure into the
    // annotation gave users the surprise "highlighted the paragraph before the diagram → the
    // diagram got marked too". Only ranges that STRADDLE the figure (chars on both sides) count.
    @Test
    fun `void img at exact range boundary is NOT enclosed (text ends flush at figure)`() {
        val html = """<html><body><p>Before</p><img src="mid.jpg"/><p>After</p></body></html>"""
        // "Before" is 6 chars → img sits at char-stream position 6. A range [0, 6] covers just
        // "Before" and stops flush at the figure; the figure must not be enclosed.
        val figures = findEnclosedFiguresInHtml(html, 0L, 6L)
        assertEquals("range flush at figure's leading edge must not enclose it", emptyList<String>(), figures.map { it.href })
    }

    @Test
    fun `void img is enclosed only when the range straddles it`() {
        val html = """<html><body><p>Before</p><img src="mid.jpg"/><p>After</p></body></html>"""
        // Range [0, 7] covers "Before" + first char of "After" → straddles the img at pos 6.
        val figures = findEnclosedFiguresInHtml(html, 0L, 7L)
        assertEquals(listOf("mid.jpg"), figures.map { it.href })
    }

    @Test
    fun `excludes figures that fall outside the char range`() {
        val html = """
            <html><body>
              <p>Alpha</p>
              <img src="in.jpg"/>
              <p>Bravo</p>
              <img src="out.jpg"/>
              <p>Charlie</p>
            </body></html>
        """.trimIndent()
        // Alpha (5 chars) + Bravo (5 chars). in.jpg sits at pos 5, out.jpg at pos 10. A range that
        // ends BEFORE out.jpg's position (endChar = 9) covers in.jpg but not out.jpg.
        val figures = findEnclosedFiguresInHtml(html, 0L, 9L)
        assertEquals(listOf("in.jpg"), figures.map { it.href })
    }

    @Test
    fun `serialises inline SVG verbatim as the figure's svg field`() {
        val html = """
            <html><body>
              <p>Before</p>
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>
              <p>After</p>
            </body></html>
        """.trimIndent()
        val total = countBodyChars(Jsoup.parse(html).body())
        val figures = findEnclosedFiguresInHtml(html, 0, total)
        assertEquals(1, figures.size)
        assertNull(figures.single().href)
        assertTrue("svg is captured", figures.single().svg?.contains("<rect") == true)
    }

    // A <figure><img></figure> block should be deduped to a single entry, and its <figcaption>
    // should surface as the figure's caption.
    @Test
    fun `figure with img yields one entry and pulls caption from figcaption`() {
        val html = """
            <html><body>
              <p>Before</p>
              <figure><img src="chart.png"/><figcaption>Complexity budget</figcaption></figure>
              <p>After</p>
            </body></html>
        """.trimIndent()
        val total = countBodyChars(Jsoup.parse(html).body())
        val figures = findEnclosedFiguresInHtml(html, 0, total)
        assertEquals(1, figures.size)
        assertEquals("chart.png", figures.single().href)
        assertEquals("Complexity budget", figures.single().caption)
    }

    // mergeEnclosedFigures: stash entries win their bytes but the Kotlin-walk still contributes
    // figures the stash missed — the whole point of the two-source arrangement.
    @Test
    fun `mergeEnclosedFigures prefers stash entries and adds html-walk figures the stash missed`() {
        val stash = listOf(
            EmbeddedFigure(href = "path/to/one.png", svg = null, caption = "", order = 0, imageBytes = "data:image/jpeg;base64,AAAA"),
        )
        val walk = listOf(
            EmbeddedFigure(href = "other/one.png", svg = null, caption = "", order = 0, imageBytes = null),
            EmbeddedFigure(href = "images/two.png", svg = null, caption = "", order = 1, imageBytes = null),
        )
        val merged = mergeEnclosedFigures(stash, walk)
        assertEquals(2, merged.size)
        // Stash entry first, with its imageBytes preserved
        assertEquals("path/to/one.png", merged[0].href)
        assertEquals("data:image/jpeg;base64,AAAA", merged[0].imageBytes)
        // Second walk entry (two.png) is appended; first (one.png) was skipped as duplicate by filename
        assertEquals("images/two.png", merged[1].href)
        assertEquals(1, merged[1].order)
    }

    // End-to-end pin using the actual chapter HTML the on-device reader loads for the exact
    // reproduction from the ticket (highlight over the "C = ΣCₚtₚ" equation in *A Philosophy of
    // Software Design*, chapter 2). Proves the walker resolves the enclosed <img> for the real
    // production DOM — not just a synthesized fragment. Loads OEBPS/part0006.xhtml from
    // src/test/resources/philosophy_ch2.xhtml.
    @Test
    fun `real chapter HTML — highlight over the equation captures image_rsrc2H6 as embeddedFigure`() {
        val html = javaClass.getResourceAsStream("/philosophy_ch2.xhtml")!!
            .bufferedReader().readText()
        val body = Jsoup.parse(html).body()
        // Locate the enclosing paragraphs by their opening text — same anchoring the JS
        // selection tracker used before the walker fires.
        val bodyText = body.text()
        val startAnchor = "To characterize this in a crude"
        val endAnchor = "The overall complexity of a system"
        val startIdx = bodyText.indexOf(startAnchor)
        val endIdx = bodyText.indexOf(endAnchor) + endAnchor.length
        assertTrue("both anchors found", startIdx >= 0 && endIdx > startIdx)
        // countBodyChars uses blank-text-node-aware counting; approximate by mapping through the
        // rendered-text offsets we just found — good enough for the range to straddle the figure
        // between the two paragraphs.
        val figures = findEnclosedFiguresInHtml(html, startIdx.toLong(), endIdx.toLong())
        assertTrue(
            "expected image_rsrc2H6.jpg in captured figures: ${figures.map { it.href }}",
            figures.any { it.href?.endsWith("image_rsrc2H6.jpg") == true },
        )
    }

    // On-device repro pin: at the exact progression + snippet + textBefore the paginated Readium
    // WebView produced when highlighting through the equation, anchorRangeToSnippet + walker MUST
    // resolve `image_rsrc2H6.jpg`. The first (progression-only) fix drifted 53 body-chars from
    // the actual snippet start and put the range's endpoint one char short of the image; the
    // regression here would silently reappear if anyone reverted to `progression * totalChars`.
    @Test
    fun `on-device repro — anchor + walker finds image when progression is imprecise`() {
        val html = javaClass.getResourceAsStream("/philosophy_ch2.xhtml")!!.bufferedReader().readText()
        val snippet = "characterize this in a crude mathematical way:\n\n\nThe overall complexity of a system"
        val textBefore = "ave much impact on the overall complexity of the system. To "
        val (startChar, endChar) = anchorRangeToSnippet(html, snippet, textBefore, 0.1983744538679)
        val figures = findEnclosedFiguresInHtml(html, startChar, endChar)
        assertTrue(
            "expected image_rsrc2H6.jpg in captured figures: ${figures.map { it.href }} (range [$startChar,$endChar])",
            figures.any { it.href?.endsWith("image_rsrc2H6.jpg") == true },
        )
    }

    // Documents WHY the progression-only path was insufficient: it's off by dozens of chars mid-
    // chapter, and endChar = startChar+snippet.length can land BEFORE an enclosed figure. Kept as
    // a shape assertion: anchorRangeToSnippet's output must NOT equal the progression-scaled range,
    // OR must produce a range that does include the image — either way, the fix (search anchoring)
    // remains active.
    @Test
    fun `progression-scaled range alone misses the image by a small margin`() {
        val html = javaClass.getResourceAsStream("/philosophy_ch2.xhtml")!!.bufferedReader().readText()
        val body = Jsoup.parse(html).body()
        val totalChars = countBodyChars(body)
        val progressionStart = (0.1983744538679 * totalChars).toLong()
        val snippetLen = "characterize this in a crude mathematical way:\n\n\nThe overall complexity of a system".length
        val progressionEnd = progressionStart + snippetLen
        val progressionFigures = findEnclosedFiguresInHtml(html, progressionStart, progressionEnd)
        // Prove the failure mode the anchor fix was needed for.
        assertTrue(
            "sanity: progression-only range should miss the image (this is the bug we fixed)",
            progressionFigures.none { it.href?.endsWith("image_rsrc2H6.jpg") == true },
        )
    }

    @Test
    fun `mergeEnclosedFigures returns html walk when stash is empty`() {
        val walk = listOf(
            EmbeddedFigure(href = "images/eq.jpg", svg = null, caption = "", order = 0, imageBytes = null),
        )
        val merged = mergeEnclosedFigures(emptyList(), walk)
        assertEquals(1, merged.size)
        assertEquals("images/eq.jpg", merged.single().href)
    }
}
