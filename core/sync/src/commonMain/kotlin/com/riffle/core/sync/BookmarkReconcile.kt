package com.riffle.core.sync

/**
 * The set-reconcile of one item's audiobook bookmarks against the Source's bookmarks capability —
 * the sweep's seam over [AudiobookBookmarkReconciler] so its orchestration stays testable without
 * Room or the network.
 */
fun interface BookmarkReconcile {
    suspend fun reconcile(sourceId: String, itemId: String)
}
