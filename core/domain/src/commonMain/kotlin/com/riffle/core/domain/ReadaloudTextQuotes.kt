package com.riffle.core.domain


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
 *
 * The one DOM-bound step — pulling each sentence span's (id, text) out of a chapter — is supplied
 * by the caller as [SentenceSpanReader], because the parser differs per platform (jsoup on JVM,
 * ksoup on Kotlin/Native). Everything downstream of that, including the neighbour-context
 * windowing, is shared so both platforms anchor highlights to identical quotes.
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
    fun build(chapters: List<EpubChapterHtml>, spans: SentenceSpanReader): Map<String, SentenceQuote> {
        val out = LinkedHashMap<String, SentenceQuote>()
        for (chapter in chapters) {
            for ((id, quote) in quotesForChapter(chapter.html, spans)) {
                out[id] = quote
            }
        }
        return out
    }

    /**
     * Map every sentence span id across [chapters] to the href of the chapter it lives in.
     *
     * "Play from here" resolves the tapped sentence by searching the rendered page for each sentence's
     * short text prefix. Run against the WHOLE book that misfires when a phrase recurs: e.g. The Martian
     * ch16's compound sentence "…I'd… He thought for a moment." contains the standalone ch8 sentence
     * "He thought for a moment." — selecting inside it matches the FOREIGN ch8 sentence's start and jumps
     * narration there. Scoping the candidate sentences to the chapter being read removes the cross-chapter
     * matches, so the resolver can only land on a sentence that genuinely belongs to this page.
     */
    fun sentenceChapterHrefs(chapters: List<EpubChapterHtml>, spans: SentenceSpanReader): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chapter in chapters) {
            for (id in quotesForChapter(chapter.html, spans).keys) out[id] = chapter.href
        }
        return out
    }

    /** Map each sentence span's id → its [SentenceQuote] within one chapter's [html]. */
    fun quotesForChapter(html: String, spans: SentenceSpanReader): Map<String, SentenceQuote> {
        // Document order is preserved, so neighbours in this list are neighbours in the prose.
        val sentences = spans.read(html).filter { SENTENCE_ID.matches(it.id) }
        val out = LinkedHashMap<String, SentenceQuote>()
        sentences.forEachIndexed { i, span ->
            val highlight = span.text
            if (highlight.isBlank()) return@forEachIndexed
            out[span.id] = SentenceQuote(
                before = if (i > 0) sentences[i - 1].text.takeLast(CONTEXT_CHARS) else "",
                highlight = highlight,
                after = if (i < sentences.lastIndex) sentences[i + 1].text.take(CONTEXT_CHARS) else "",
            )
        }
        return out
    }
}

/** One `<span id="…">` of a chapter: its id and its rendered text. */
data class SentenceSpan(val id: String, val text: String)

/**
 * Reads a chapter's id-bearing spans in document order. Implemented per platform because the HTML
 * parser differs; an unparseable chapter returns an empty list rather than failing the whole book.
 */
fun interface SentenceSpanReader {
    fun read(html: String): List<SentenceSpan>
}
