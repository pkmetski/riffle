package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The primary, always-queryable Annotations store (ADR 0025). Local Room is the source of truth;
 * sync is a later, additive layer. v1 creates and reads Highlights and Bookmarks on the ABS side.
 */
interface AnnotationStore {

    /** Live, non-deleted highlights for an ABS Library Item, oldest first. */
    fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted bookmarks for an ABS Library Item, oldest first. */
    fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>>

    /**
     * Create a Highlight at [cfi] (a CFI range) with the default colour, capturing the selected
     * [textSnippet] and its [chapterHref]. Mints a fresh UUID and stamps the current device + time.
     */
    suspend fun createHighlight(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String = "",
        textAfter: String = "",
        color: String = DEFAULT_COLOR,
    ): Annotation

    /**
     * Create a Bookmark at [cfi] (a CFI point = top-of-viewport position), capturing surrounding
     * [textSnippet] and [chapterHref] as re-anchoring fallback. Mints a fresh UUID.
     */
    suspend fun createBookmark(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
    ): Annotation

    /** Tombstone an annotation so the delete can later propagate to other devices. */
    suspend fun delete(id: String)

    /** Recolour an existing highlight in place, bumping its updatedAt. */
    suspend fun recolor(id: String, color: String)

    companion object {
        const val DEFAULT_COLOR = "yellow"
    }
}
