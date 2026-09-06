package com.riffle.core.domain

/**
 * JVM extension of [AudiobookCacheRepository] exposing [localSession] and [awaitCachedAudiobook],
 * which depend on [AudiobookSession] (carries a [java.io.File] ref) and cannot be in commonMain.
 */
interface JvmAudiobookCacheRepository : AudiobookCacheRepository {
    /** A playable session backed by cached local files (`file://` track URLs), or null. */
    fun localSession(sourceId: String, itemId: String): AudiobookSession?

    /**
     * Downloads all tracks in [session] to the cache dir. No-op if already cached. Silently
     * discards any download error so callers (streaming path in the player VM) are unaffected.
     */
    suspend fun awaitCachedAudiobook(sourceId: String, itemId: String, session: AudiobookSession)
}
