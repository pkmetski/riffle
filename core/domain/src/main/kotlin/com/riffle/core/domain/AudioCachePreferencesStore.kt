package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Per-Storyteller-Server cap on the Readaloud audio Cache. Governs how large the auto-cached
 * (evictable) audio-bundle area may grow before [LruCacheEvictor] sheds the least-recently-used
 * bundles. Permanent Downloads are unaffected (ADR 0023).
 */
interface AudioCachePreferencesStore {
    fun capBytes(serverId: String): Flow<Long>
    suspend fun setCapBytes(serverId: String, capBytes: Long)

    companion object {
        const val DEFAULT_CAP_BYTES: Long = 2L * 1024 * 1024 * 1024 // 2 GB
    }
}
