package com.riffle.core.domain

import org.jsoup.Jsoup

/**
 * A narrated sentence located as a Readium text quote: the sentence itself ([highlight]) plus a
 * little surrounding prose ([before]/[after]) to disambiguate when the same words recur on a page.
 */
data class SentenceQuote(
    val before: String,
    val highlight: String,
    val after: String,
)

/**
 * Builds `fragmentRef → SentenceQuote` for a readaloud EPUB, so the synced highlight can be anchored
 * by *text* instead of by the sentence span's id.
 *
 * Why: a readaloud EPUB carries Storyteller's per-sentence spans (`<span id="…-sN">`), and the
 * highlight historically targeted them via a `#id` cssSelector. But when the reader renders the ABS
 * EPUB, Readium drops those id-only spans from the HTML it serves (it keeps class-bearing spans),
 * so the cssSelector resolves to nothing and no decoration is drawn. Readium's decoration
 * positioner falls back to a TextQuoteAnchor search over `document.body` whenever the locator
 * carries `text.highlight`, so anchoring by the sentence's text survives the span stripping and
 * lands the highlight on the rendered prose. Sentence text is extracted from the EPUB's own spans
 * (which exist on disk even though Readium omits them when serving).
 *
 * Pure given the spine chapter HTML; an unparseable chapter contributes nothing rather than failing
 * the whole book.
 */
object ReadaloudTextQuotes {

    /** Sentence spans follow Storyteller's `…-s<n>` id convention (e.g. `id259-s0`, `c001-s12`). */
    private val SENTENCE_ID = Regex(".*-s\\d+")

    /** Characters of neighbouring prose kept as prefix/suffix context for disambiguation. */
    private const val CONTEXT_CHARS = 30

    /**
     * Map every sentence span id across [chapters] to its [SentenceQuote].
     *
     * Keyed by the bare span id (e.g. `c008-s0`, `id259-s5`), not `href#id`: Storyteller's ids are
     * unique within a book, and the readaloud track's fragment refs resolve their href differently
     * (root-relative `OEBPS/xhtml/…`) than the EPUB's manifest does (OPF-relative `xhtml/…`), so
     * matching on the id alone sidesteps that prefix mismatch. Callers look up by `ref` after `#`.
     */
    fun build(chapters: List<EpubChapterHtml>): Map<String, SentenceQuote> {
        val out = LinkedHashMap<String, SentenceQuote>()
        for (chapter in chapters) {
            for ((id, quote) in quotesForChapter(chapter.html)) {
                out[id] = quote
            }
        }
        return out
    }

    /** Map each sentence span's id → its [SentenceQuote] within one chapter's [html]. */
    fun quotesForChapter(html: String): Map<String, SentenceQuote> {
        val doc = try { Jsoup.parse(html) } catch (_: Exception) { return emptyMap() }
        // Document order is preserved, so neighbours in this list are neighbours in the prose.
        val spans = doc.select("span[id]").filter { SENTENCE_ID.matches(it.id()) }
        val texts = spans.map { it.text() }
        val out = LinkedHashMap<String, SentenceQuote>()
        spans.forEachIndexed { i, span ->
            val highlight = texts[i]
            if (highlight.isBlank()) return@forEachIndexed
            out[span.id()] = SentenceQuote(
                before = if (i > 0) texts[i - 1].takeLast(CONTEXT_CHARS) else "",
                highlight = highlight,
                after = if (i < texts.lastIndex) texts[i + 1].take(CONTEXT_CHARS) else "",
            )
        }
        return out
    }
}
