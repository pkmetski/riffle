package com.riffle.core.domain

enum class ContentCacheArtifactKind {
    Epub,
    Pdf,
    Audiobook,
    Cbz,
}

data class ContentCacheKey(
    val sourceId: String,
    val itemId: String,
    val kind: ContentCacheArtifactKind,
)

interface ContentCacheAccessStore {
    suspend fun markAccessed(key: ContentCacheKey)
    suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long)
    suspend fun lastAccessedAt(key: ContentCacheKey): Long?
    suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?>
    suspend fun forget(key: ContentCacheKey)
}
