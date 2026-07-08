package com.riffle.core.catalog

/**
 * A named audiobook bookmark on a Source. Cross-source, cross-device set semantics; the reconciler
 * unions this Source's set with the local set (last-writer-wins on delete conflicts).
 */
data class CatalogBookmark(
    val itemId: String,
    val timeSec: Int,
    val title: String,
    val createdAt: Long,
)
