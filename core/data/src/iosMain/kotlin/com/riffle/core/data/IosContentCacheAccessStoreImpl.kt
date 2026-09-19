package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheKey
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed ContentCacheAccessStore for iOS — Android's ContentCacheAccessStoreImpl
// uses androidx.datastore.preferences.core.Preferences, which has no iOS equivalent, so this
// mirrors CoverGridDensityStoreImpl's platform-store pattern instead of moving that class.
class IosContentCacheAccessStoreImpl(private val clock: Clock) : ContentCacheAccessStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun markAccessed(key: ContentCacheKey) {
        markAccessedAt(key, clock.nowMs())
    }

    override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) {
        defaults.setInteger(timestampMs, forKey = prefKey(key))
    }

    override suspend fun lastAccessedAt(key: ContentCacheKey): Long? {
        val k = prefKey(key)
        return if (defaults.objectForKey(k) != null) defaults.integerForKey(k) else null
    }

    override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> =
        keys.associateWith { lastAccessedAt(it) }

    override suspend fun forget(key: ContentCacheKey) {
        defaults.removeObjectForKey(prefKey(key))
    }

    private fun prefKey(key: ContentCacheKey) =
        "content_cache_access:${key.kind.name}:${key.sourceId}:${key.itemId}"
}
