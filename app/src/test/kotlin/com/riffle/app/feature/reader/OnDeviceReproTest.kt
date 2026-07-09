package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceReproTest {

    // Regression pin: on-device the selection Readium hands us for a highlight that CROSSES an
    // enclosed figure includes a "\n\n\n" run between the pre-figure and post-figure paragraphs.
    // After collapsing whitespace, cleanSnippet contains "way: The overall…" (one space), but the
    // body-text stream — built from non-blank text nodes concatenated verbatim — has NO chars for
    // the void <img> between them, so it reads "way:The overall…" (no space). Matching the full 40
    // chars of cleanSnippet against that stream fails and the anchor falls back to progression *
    // totalChars, which lands 60+ chars short of "characterize" and misses the img entirely. The
    // fix anchors on ONLY the first paragraph of the snippet, before the cross-figure boundary.
    @Test
    fun `snippet that crosses an enclosed figure still anchors to the correct body-char range`() {
        val html = javaClass.getResourceAsStream("/philosophy_ch2.xhtml")!!.bufferedReader().readText()
        val snippet = "this in a crude mathematical way:\n\n\nThe overall complexity of a system (C)"
        val textBefore = "impact on the overall complexity of the system. To characterize "
        val (start, end) = anchorRangeToSnippet(html, snippet, textBefore, 0.1983744538679)
        val figures = findEnclosedFiguresInHtml(html, start, end)
        assertTrue(
            "expected image_rsrc2H6.jpg in figures for range [$start,$end]: ${figures.map { it.href }}",
            figures.any { it.href?.endsWith("image_rsrc2H6.jpg") == true },
        )
    }
}
