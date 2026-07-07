package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface AnnotationStore {

    /** Live, non-deleted highlights for an ABS Library Item, oldest first. */
    fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted bookmarks for an ABS Library Item, oldest first. */
    fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks for an ABS Library Item, sorted by reading position. */
    fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks across every item for a server, oldest first. */
    fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>>

    suspend fun createHighlight(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String = "",
        textAfter: String = "",
        color: String = DEFAULT_COLOR,
        spineIndex: Int = 0,
        progression: Double = 0.0,
    ): Annotation

    suspend fun createBookmark(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
    ): Annotation

    suspend fun delete(id: String)
    suspend fun recolor(id: String, color: String)
    suspend fun updateNote(id: String, note: String?)

    /** Update the user-editable title of a bookmark, bumping its updatedAt. */
    suspend fun renameBookmark(id: String, title: String)

    /** One-shot lookup of the annotation that exactly matches [cfi] for this item, or null. Used
     *  at open-from-library so the continuous reader can scroll to the annotation's DOM decoration
     *  (a precise post-reflow Y) instead of guessing from char-fraction × measured-WebView-height. */
    suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation?

    companion object {
        const val DEFAULT_COLOR = "yellow"
    }
}
