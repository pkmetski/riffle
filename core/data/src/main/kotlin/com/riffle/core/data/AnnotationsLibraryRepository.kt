package com.riffle.core.data

import kotlinx.coroutines.flow.Flow

/**
 * One row per book (ABS Library Item) with at least one live highlight on a server — powers the
 * Annotations View library list. [title]/[author]/[coverUrl] are null when the book's
 * `library_items` row is no longer cached locally (e.g. removed from the server library while the
 * highlight itself survives), in which case the UI falls back to a text-only card.
 */
data class AnnotatedBook(
    val serverId: String,
    val itemId: String,
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val highlightCount: Int,
    val latestUpdatedAt: Long,
)

interface AnnotationsLibraryRepository {
    /** Books with at least one live highlight on [serverId], most recently updated first. */
    fun observeAnnotatedBooks(serverId: String): Flow<List<AnnotatedBook>>
}
