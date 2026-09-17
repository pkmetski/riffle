package com.riffle.core.domain

interface AudiobookCacheRepository {
    fun isCached(sourceId: String, itemId: String): Boolean
    suspend fun remove(sourceId: String, itemId: String): Long

    /** A playable session backed by cached local files, or null when not cached / not supported. */
    fun localSession(sourceId: String, itemId: String): AudiobookSession? = null

    /**
     * Downloads all tracks in [session] to the cache dir. No-op if already cached or not supported.
     * Silently discards any download error so callers (streaming path in the player VM) are unaffected.
     */
    suspend fun awaitCachedAudiobook(sourceId: String, itemId: String, session: AudiobookSession): Unit = Unit
}
