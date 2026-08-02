package com.riffle.core.sync

/**
 * The subset of Source capabilities required by shared reconciliation.
 *
 * The platform host adapts its catalog/source registry to this port, keeping catalog streaming and
 * credential resolution out of `core:sync`.
 */
fun interface SyncSourceResolver {
    suspend fun resolve(sourceId: String): SyncSource?
}

interface SyncSource {
    val supportsEbookProgress: Boolean
    val supportsAudiobookProgress: Boolean
    val bookmarks: BookmarkRemote?
}

interface BookmarkRemote {
    suspend fun listAll(): List<RemoteBookmark>
    suspend fun create(itemId: String, timeSec: Int, title: String)
    suspend fun delete(itemId: String, timeSec: Int)
    suspend fun rename(itemId: String, timeSec: Int, title: String)
}

data class RemoteBookmark(
    val itemId: String,
    val timeSec: Int,
    val title: String,
    val createdAt: Long,
)
