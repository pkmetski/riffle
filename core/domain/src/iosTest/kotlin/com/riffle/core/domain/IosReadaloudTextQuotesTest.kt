package com.riffle.core.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * iOS counterpart to core:domain's jvmTest ReadaloudTextQuotesTest — the same scenarios against
 * the ksoup-backed [IosSentenceSpanReader]. The two readers must extract identical sentence text
 * and neighbour context, or a readaloud highlight anchors to different prose per platform.
 */
class IosReadaloudTextQuotesTest {

    // The Martian's id scheme (idNNN-sM), with a sentence wrapped through a nested styling span.
    private val martianChapter = """
        <html><body class="calibre">
          <h1 id="calibre_pb_1"><span id="id259-s0"><span class="smallcaps">LOG ENTRY: SOL 63</span></span></h1>
          <p class="para"><span id="id259-s1">I finished making water some time ago. </span><span id="id259-s2">The potatoes are growing nicely. </span><span id="id259-s3">Things are stable here on Mars.</span></p>
        </body></html>
    """.trimIndent()

    // Project Hail Mary's id scheme (cNNN-sM), under an OEBPS-style chapter href.
    private val phmChapter = """
        <html><body><p>
          <span id="c008-s0">By the time we reached Geneva, I'd completely lost track.</span>
          <span id="c008-s1">The computer models for the Astrophage breeder were promising.</span>
        </p></body></html>
    """.trimIndent()

    @Test
    fun `highlight is the sentence text even through a nested span`() {
        val quotes = ReadaloudTextQuotes.quotesForChapter(martianChapter, IosSentenceSpanReader)
        assertEquals(quotes["id259-s0"]!!.highlight, "LOG ENTRY: SOL 63")
        assertEquals(quotes["id259-s1"]!!.highlight, "I finished making water some time ago.")
    }

    @Test
    fun `before and after come from the neighbouring sentences`() {
        val s2 = ReadaloudTextQuotes.quotesForChapter(martianChapter, IosSentenceSpanReader)["id259-s2"]!!
        assertEquals(s2.highlight, "The potatoes are growing nicely.")
        assertTrue(s2.before.endsWith("water some time ago."), "before was: ${s2.before}")
        assertTrue(s2.after.startsWith("Things are stable"), "after was: ${s2.after}")
    }

    @Test
    fun `first sentence has empty before last has empty after`() {
        val quotes = ReadaloudTextQuotes.quotesForChapter(martianChapter, IosSentenceSpanReader)
        assertEquals(quotes["id259-s0"]!!.before, "")
        assertEquals(quotes["id259-s3"]!!.after, "")
    }

    @Test
    fun `context prefix and suffix are capped at 30 chars`() {
        val s2 = ReadaloudTextQuotes.quotesForChapter(martianChapter, IosSentenceSpanReader)["id259-s2"]!!
        assertTrue(s2.before.length <= 30, "before too long: ${s2.before.length}")
        assertTrue(s2.after.length <= 30, "after too long: ${s2.after.length}")
    }

    @Test
    fun `non-sentence spans and structural ids are ignored`() {
        val quotes = ReadaloudTextQuotes.quotesForChapter(martianChapter, IosSentenceSpanReader)
        assertNull(quotes["calibre_pb_1"])
        assertTrue(quotes.keys.all { it.matches(Regex(".*-s\\d+")) })
    }

    @Test
    fun `supports the cNNN-sM id scheme too`() {
        val quotes = ReadaloudTextQuotes.quotesForChapter(phmChapter, IosSentenceSpanReader)
        assertEquals(quotes["c008-s0"]!!.highlight, "By the time we reached Geneva, I'd completely lost track.")
        assertEquals(quotes["c008-s1"]!!.highlight, "The computer models for the Astrophage breeder were promising.")
    }

    // The bug that cost us: the track's fragment refs are root-relative (OEBPS/xhtml/…) while the EPUB
    // manifest is OPF-relative, so keying by href#id never matched. Keying by the (book-unique) span id
    // makes the lookup independent of how either side spells the href.
    @Test
    fun `lookup is by span id independent of chapter href`() {
        val chapters = listOf(
            EpubChapterHtml(href = "text/part0012_split_001.html", html = martianChapter),
            EpubChapterHtml(href = "OEBPS/xhtml/Weir_9780593135211_epub3_c008_r1.xhtml", html = phmChapter),
        )
        val all = ReadaloudTextQuotes.build(chapters, IosSentenceSpanReader)
        // both books' sentences resolve by bare id, with no href prefix involved
        assertEquals(all["id259-s1"]!!.highlight, "I finished making water some time ago.")
        assertEquals(all["c008-s1"]!!.highlight, "The computer models for the Astrophage breeder were promising.")
        // and the keys carry no '#'/href
        assertTrue(all.keys.none { it.contains('#') || it.contains('/') })
    }

    @Test
    fun `entities and punctuation in sentence text are preserved`() {
        val html = "<html><body><p><span id=\"x-s0\">&ldquo;Don&rsquo;t panic,&rdquo; he said&mdash;calmly.</span></p></body></html>"
        val q = ReadaloudTextQuotes.quotesForChapter(html, IosSentenceSpanReader)["x-s0"]!!
        // curly quotes / em-dash decoded to the same glyphs Readium will search the rendered DOM for
        assertEquals(q.highlight, "“Don’t panic,” he said—calmly.")
    }

    @Test
    fun `blank sentence spans are skipped`() {
        val html = "<html><body><p><span id=\"x-s0\">   </span><span id=\"x-s1\">Real text.</span></p></body></html>"
        val quotes = ReadaloudTextQuotes.quotesForChapter(html, IosSentenceSpanReader)
        assertNull(quotes["x-s0"])
        assertEquals(quotes["x-s1"]!!.highlight, "Real text.")
    }

    // Feeds "Play from here" chapter-scoping (so a phrase recurring across chapters can't resolve to
    // the wrong chapter's clip — The Martian ch16 → ch8). Every sentence id maps to the href it lives in.
    @Test
    fun `sentenceChapterHrefs maps each span id to its chapter href`() {
        val chapters = listOf(
            EpubChapterHtml(href = "text/part0013.html", html = martianChapter),
            EpubChapterHtml(href = "text/part0021.html", html = phmChapter),
        )
        val map = ReadaloudTextQuotes.sentenceChapterHrefs(chapters, IosSentenceSpanReader)
        assertEquals(map["id259-s1"], "text/part0013.html")
        assertEquals(map["id259-s3"], "text/part0013.html")
        assertEquals(map["c008-s0"], "text/part0021.html")
        // structural / non-sentence ids never enter the map
        assertNull(map["calibre_pb_1"])
    }

    @Test
    fun `unparseable or empty chapter contributes nothing never throws`() {
        assertTrue(ReadaloudTextQuotes.quotesForChapter("", IosSentenceSpanReader).isEmpty())
        assertTrue(ReadaloudTextQuotes.build(emptyList(), IosSentenceSpanReader).isEmpty())
    }
}
