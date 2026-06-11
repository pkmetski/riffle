package com.riffle.core.domain

sealed class AudiobookDownloadResult {
    data object Success : AudiobookDownloadResult()
    data class NetworkError(val cause: Throwable) : AudiobookDownloadResult()
}

/**
 * Permanent offline copy of an [Audiobook] — the ebook-Download analogue for audio (ADR 0029). An
 * audiobook is several ABS tracks, so a download is a *directory* of track files plus a manifest that
 * records the timeline (per-track offsets/durations + chapters) so playback reconstructs the book
 * offline without re-opening an ABS play session. v1 is download-or-nothing; there is no auto-cache
 * tier for audio yet.
 */
interface AudiobookDownloadRepository {
    fun isDownloaded(serverId: String, itemId: String): Boolean

    /** A playable session backed by the downloaded local files (`file://` track URLs), or null. */
    fun localSession(serverId: String, itemId: String): AudiobookSession?

    /** Downloads every track of the audiobook to permanent storage, reporting cumulative bytes. */
    suspend fun download(
        serverId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult

    /** Removes the downloaded copy; returns bytes freed. */
    suspend fun remove(serverId: String, itemId: String): Long
}
