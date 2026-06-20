package com.riffle.app.feature.library

import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.LibraryItem

/** One annotation match in a [LibraryItemsViewModel] search, carrying its owning book's display fields. */
data class AnnotationSearchResult(
    val annotation: Annotation,
    val bookTitle: String,
    val bookCoverUrl: String?,
)

/** One audiobook-bookmark match in a library search, carrying its owning book's display fields. */
data class AudiobookBookmarkSearchResult(
    val bookmark: AudiobookBookmark,
    val bookTitle: String,
    val bookCoverUrl: String?,
)

/**
 * Filter [annotations] to those whose note text, highlighted snippet, or bookmark title contains
 * [query] (case-insensitive), scoped to the books in [libraryItems], and pair each with its book's
 * title + cover. Returns empty for a blank query. Order follows [annotations] (caller decides sort).
 */
fun searchAnnotations(
    annotations: List<Annotation>,
    libraryItems: List<LibraryItem>,
    query: String,
): List<AnnotationSearchResult> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val byId = libraryItems.associateBy { it.id }
    return annotations.mapNotNull { a ->
        val item = byId[a.itemId] ?: return@mapNotNull null // scope to this library's items
        if (!annotationMatches(a, q)) return@mapNotNull null
        AnnotationSearchResult(annotation = a, bookTitle = item.title, bookCoverUrl = item.coverUrl)
    }
}

private fun annotationMatches(a: Annotation, query: String): Boolean =
    a.note?.contains(query, ignoreCase = true) == true ||
        a.textSnippet.contains(query, ignoreCase = true) ||
        a.bookmarkTitle.contains(query, ignoreCase = true)

/**
 * Filter [bookmarks] to those whose title contains [query] (case-insensitive), scoped to the
 * books in [libraryItems], and pair each with its book's title + cover. Returns empty for a
 * blank query. Order follows [bookmarks] (earliest position first per DAO contract).
 */
fun searchAudiobookBookmarks(
    bookmarks: List<AudiobookBookmark>,
    libraryItems: List<LibraryItem>,
    query: String,
): List<AudiobookBookmarkSearchResult> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val byId = libraryItems.associateBy { it.id }
    return bookmarks.mapNotNull { bm ->
        val item = byId[bm.itemId] ?: return@mapNotNull null
        if (!bm.title.contains(q, ignoreCase = true)) return@mapNotNull null
        AudiobookBookmarkSearchResult(bookmark = bm, bookTitle = item.title, bookCoverUrl = item.coverUrl)
    }
}
