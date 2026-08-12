package com.riffle.core.domain

object NoopContentCacheAccessStore : ContentCacheAccessStore {
    override suspend fun markAccessed(key: ContentCacheKey) = Unit
    override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) = Unit
    override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = null
    override suspend fun forget(key: ContentCacheKey) = Unit
}
