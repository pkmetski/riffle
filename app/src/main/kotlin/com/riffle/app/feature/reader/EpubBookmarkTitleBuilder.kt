package com.riffle.app.feature.reader

import kotlin.math.roundToInt

object EpubBookmarkTitleBuilder {

    fun build(
        chapterHref: String,
        chapterProgression: Double,
        totalProgression: Double?,
        tocEntries: List<TocEntry>,
    ): String {
        val chapterTitle = findTitle(tocEntries, chapterHref)
        if (chapterTitle != null) {
            val pct = (chapterProgression * 100).roundToInt().coerceIn(0, 100)
            return "$chapterTitle · $pct%"
        }
        val fallback = totalProgression ?: chapterProgression
        return "${(fallback * 100).roundToInt().coerceIn(0, 100)}%"
    }

    private fun findTitle(entries: List<TocEntry>, href: String): String? {
        val normalizedHref = normalizeEpubHref(href)
        for (entry in entries) {
            if (normalizeEpubHref(entry.href) == normalizedHref && entry.title.isNotBlank()) return entry.title
            val child = findTitle(entry.children, href)
            if (child != null) return child
        }
        return null
    }
}
