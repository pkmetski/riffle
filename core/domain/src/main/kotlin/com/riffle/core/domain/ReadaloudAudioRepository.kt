package com.riffle.core.domain

import java.io.File

sealed interface AudioDownloadResult {
    data object Success : AudioDownloadResult
    data object NoBundle : AudioDownloadResult
    data class NetworkError(val cause: Throwable) : AudioDownloadResult
}

/**
 * Manages the Readaloud audio bundle (the Storyteller synced EPUB — ADR 0023) for the reader: its
 * local presence, its parsed Media Overlay [ReadaloudTrack], download (with progress) into the
 * permanent Downloads area, removal, and LRU eviction of the auto-cached area against the
 * per-server cap.
 */
interface ReadaloudAudioRepository {
    /** True when the synced bundle is present locally (Downloads or Cache). */
    fun isAudioAvailable(itemId: String): Boolean

    /** The local synced-bundle file, or null if not present. */
    fun bundleFile(itemId: String): File?

    /** Parses the Media Overlay timeline out of the local bundle, or null if no bundle / no overlays. */
    suspend fun readTrack(itemId: String): ReadaloudTrack?

    /** The download size in bytes (server Content-Length), or null if it can't be probed. */
    suspend fun probeSizeBytes(itemId: String): Long?

    /** Downloads the synced bundle into permanent Downloads with resume + progress. */
    suspend fun downloadAudio(itemId: String, onProgress: (downloaded: Long, total: Long) -> Unit): AudioDownloadResult

    /** Removes the downloaded bundle; returns the number of bytes freed. */
    suspend fun removeAudio(itemId: String): Long

    /** Evicts least-recently-used cached bundles for the active server until under its cap. */
    suspend fun enforceCacheCap()
}
