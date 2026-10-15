package com.riffle.feature.reader.highlights

import com.riffle.core.models.TocEntry

/**
 * Resolves the best available human-readable title for an elided chapter.
 *
 * Priority: (1) title from the book's TOC, (2) [derived] if it looks like a real title,
 * (3) "Chapter N" fallback when [derived] is an unhelpful auto-generated filename.
 */
fun elidedChapterTitle(href: String, derived: String, toc: List<TocEntry>, elidedIndex: Int): String {
    resolveChapterTitle(href, toc)?.let { return it }
    if (!looksUnhelpfulTitle(derived)) return derived
    return "Chapter ${elidedIndex + 1}"
}

private val UUID_TITLE_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private val SPINE_FILENAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_-]*\\d{2,}$")

fun looksUnhelpfulTitle(title: String): Boolean =
    title.isBlank() || title.equals("Chapter", ignoreCase = true) ||
    UUID_TITLE_REGEX.matches(title) || SPINE_FILENAME_REGEX.matches(title)

/**
 * Looks up a highlight's chapter title from the book's TOC by matching on the chapter href.
 * Strips the `#fragment` suffix before matching. Returns null when no TOC entry matches so the
 * caller can apply its own fallback.
 */
fun resolveChapterTitle(href: String, toc: List<TocEntry>): String? {
    val normalized = href.substringBefore('#')
    fun search(entries: List<TocEntry>): String? {
        for (entry in entries) {
            val entryHref = entry.href.substringBefore('#')
            if (entryHref == normalized || entryHref.endsWith("/$normalized") || normalized.endsWith("/$entryHref")) {
                return entry.title.ifBlank { null }
            }
            search(entry.children)?.let { return it }
        }
        return null
    }
    return search(toc)
}

/**
 * Returns the synthesised `highlights/chN.xhtml` href for the chapter containing
 * [lastAnnotationId], or null when the highlight no longer exists or [readingOrderSize]
 * would be exceeded. Used to restore the reading position when reopening Highlights mode.
 */
fun highlightsResumeChapterHref(
    chapters: List<ChapterElision>,
    lastAnnotationId: String,
    readingOrderSize: Int,
): String? {
    val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }
    val index = nonEmptyChapters.indexOfFirst { chapter -> chapter.highlights.any { it.id == lastAnnotationId } }
    if (index < 0 || index >= readingOrderSize) return null
    return "highlights/ch$index.xhtml"
}

/**
 * Inverse of [highlightsResumeChapterHref]: given a synthesised href like `highlights/ch2.xhtml`,
 * returns the id of the first highlight in that chapter so it can be persisted as the resume
 * position. Returns null for unrecognised or out-of-range hrefs.
 */
fun highlightsResumeAnnotationIdForHref(chapters: List<ChapterElision>, href: String): String? {
    val index = Regex("""highlights/ch(\d+)\.xhtml$""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }
    return nonEmptyChapters.getOrNull(index)?.highlights?.firstOrNull()?.id
}
