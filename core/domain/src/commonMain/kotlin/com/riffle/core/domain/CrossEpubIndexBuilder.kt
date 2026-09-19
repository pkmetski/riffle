package com.riffle.core.domain

/**
 * Builds a [CrossEpubIndex] from the two EPUBs of a matched readaloud book. Pure given the two
 * chapter-HTML lists (the caller reads them out of the cached EPUB bundles); chapters are aligned
 * by spine order.
 *
 * [countReadableChars] is injected because the readable-character definition lives in a
 * DOM library that differs per platform — `EpubTextChars` (jsoup) on JVM, `IosEpubTextChars`
 * (ksoup) on iOS — while the alignment itself is identical and belongs in one place. It must be
 * the same counter the CFI translator uses on that platform, or positions and index disagree.
 */
object CrossEpubIndexBuilder {

    fun build(
        absChaptersHtml: List<String>,
        storytellerChaptersHtml: List<String>,
        countReadableChars: (String) -> Long,
    ): CrossEpubIndex {
        val chapterCount = minOf(absChaptersHtml.size, storytellerChaptersHtml.size)
        val maps = (0 until chapterCount).map { i ->
            ChapterCharMap(
                absChars = countReadableChars(absChaptersHtml[i]),
                storytellerChars = countReadableChars(storytellerChaptersHtml[i]),
            )
        }
        return CrossEpubIndex(perChapter = maps)
    }
}
