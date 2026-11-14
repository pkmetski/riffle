package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheKey

class ContentCacheAccessStoreImpl(
    private val store: KeyValueStore,
    private val clock: Clock,
) : ContentCacheAccessStore {

    override suspend fun markAccessed(key: ContentCacheKey) = markAccessedAt(key, clock.nowMs())

    override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) =
        store.setLong(prefKey(key), timestampMs)

    override suspend fun lastAccessedAt(key: ContentCacheKey): Long? =
        store.getLongOrNull(prefKey(key))

    override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> =
        keys.associateWith { lastAccessedAt(it) }

    override suspend fun forget(key: ContentCacheKey) = store.remove(prefKey(key))

    private fun prefKey(key: ContentCacheKey) =
        "content_cache_access:${key.kind.name}:${key.sourceId}:${key.itemId}"
}
