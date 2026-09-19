package com.riffle.core.domain

/**
 * The DOM-backed EPUB text services the reader-sync path needs, gathered behind one seam so
 * `ReaderSyncFactory` can live in commonMain.
 *
 * Each member is DOM-library-bound (jsoup on JVM, ksoup on Kotlin/Native), and all three must
 * agree across platforms: they decide where a synced position lands, which sentence a highlight
 * anchors to, and how audio time maps to reader progress.
 */
interface ReadaloudEpubTextOps {
    /** CFI ↔ progression primitives (ADR 0013). */
    val cfiOps: EpubCfiOps

    /** Within-chapter progressions for a set of element ids, in one parse (ADR 0023). */
    fun progressionsOfElementIds(html: String, elementIds: Set<String>): Map<String, Double>

    /** Sentence spans of a chapter, for text-anchored readaloud highlights. */
    val sentenceSpans: SentenceSpanReader
}
