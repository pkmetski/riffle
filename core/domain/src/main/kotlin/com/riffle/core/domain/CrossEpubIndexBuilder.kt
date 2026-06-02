package com.riffle.core.domain

/**
 * Builds a [CrossEpubIndex] from the two EPUBs of a matched readaloud book. Pure given
 * the two chapter-HTML lists (the caller reads them out of the cached EPUB bundles);
 * reuses [EpubTextChars] so the readable-character definition matches the CFI
 * translator's exactly. Chapters are aligned by spine order.
 */
object CrossEpubIndexBuilder {

    fun build(
        absChaptersHtml: List<String>,
        storytellerChaptersHtml: List<String>,
    ): CrossEpubIndex {
        val chapterCount = minOf(absChaptersHtml.size, storytellerChaptersHtml.size)
        val maps = (0 until chapterCount).map { i ->
            ChapterCharMap(
                absChars = EpubTextChars.countReadableChars(absChaptersHtml[i]),
                storytellerChars = EpubTextChars.countReadableChars(storytellerChaptersHtml[i]),
            )
        }
        return CrossEpubIndex(perChapter = maps)
    }
}
