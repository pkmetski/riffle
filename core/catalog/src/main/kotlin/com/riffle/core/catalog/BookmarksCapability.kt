package com.riffle.core.catalog

/**
 * Server-side audiobook bookmarks. Sources that keep bookmarks per-item on the server (ABS)
 * implement this so the local set-reconciler can pull the remote set and push local mutations.
 */
interface BookmarksCapability : CatalogCapability {
    /** All bookmarks the current user has on this Source across every item. */
    suspend fun listAllBookmarks(): List<CatalogBookmark>

    /** Create a new bookmark on [itemId] at [timeSec] with [title]. */
    suspend fun createBookmark(itemId: String, timeSec: Int, title: String): CatalogBookmark

    /** Delete the bookmark on [itemId] at [timeSec]. */
    suspend fun deleteBookmark(itemId: String, timeSec: Int)

    /** Rename the bookmark on [itemId] at [timeSec]. */
    suspend fun renameBookmark(itemId: String, timeSec: Int, newTitle: String): CatalogBookmark
}
