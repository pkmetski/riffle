package com.riffle.core.data

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.riffle.core.common.Clock
import com.riffle.core.data.di.ContentCacheAccessDataStore
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ContentCacheAccessStoreImpl @Inject constructor(
    @param:ContentCacheAccessDataStore private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : ContentCacheAccessStore {

    override suspend fun markAccessed(key: ContentCacheKey) {
        markAccessedAt(key, clock.nowMs())
    }

    override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) {
        dataStore.edit { prefs ->
            prefs[prefKey(key)] = timestampMs
        }
    }

    override suspend fun lastAccessedAt(key: ContentCacheKey): Long? =
        dataStore.data.map { prefs -> prefs[prefKey(key)] }.first()

    override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> {
        val prefs = dataStore.data.first()
        return keys.associateWith { key -> prefs[prefKey(key)] }
    }

    override suspend fun forget(key: ContentCacheKey) {
        dataStore.edit { prefs ->
            prefs.remove(prefKey(key))
        }
    }
}

private fun prefKey(key: ContentCacheKey) = longPreferencesKey(
    "content_cache_access:${key.kind.name}:${key.sourceId.cacheKeyPart()}:${key.itemId.cacheKeyPart()}",
)

private fun String.cacheKeyPart(): String =
    Base64.encodeToString(
        toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )
