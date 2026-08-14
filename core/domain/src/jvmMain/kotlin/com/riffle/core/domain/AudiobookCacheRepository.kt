package com.riffle.core.domain

/**
 * Auto-cache tier for ABS audiobook tracks (ADR 0035). When a book is opened for streaming, all
 * tracks are downloaded in the background to evictable cache storage. On completion the player
 * swaps its unplayed [MediaItem]s to the local [file://][AudiobookSession.trackUrls] URLs — the
 * same swap the CBZ reader does from [NetworkImageSource] to [ArchiveImageSource].
 *
 * Unlike [AudiobookDownloadRepository] (permanent user-pinned storage), the cache dir lives under
 * [android.content.Context.getCacheDir] and can be evicted by the OS. A missing or incomplete
 * cache (no manifest) reads as not-cached and triggers a fresh download on next open.
 */
interface AudiobookCacheRepository {
    fun isCached(sourceId: String, itemId: String): Boolean

    /** A playable session backed by cached local files (`file://` track URLs), or null. */
    fun localSession(sourceId: String, itemId: String): AudiobookSession?

    /**
     * Downloads all tracks in [session] to the cache dir. No-op if already cached. Silently
     * discards any download error so callers (streaming path in the player VM) are unaffected.
     */
    suspend fun awaitCachedAudiobook(sourceId: String, itemId: String, session: AudiobookSession)

    /** Removes the cached copy; returns bytes freed. */
    suspend fun remove(sourceId: String, itemId: String): Long
}
