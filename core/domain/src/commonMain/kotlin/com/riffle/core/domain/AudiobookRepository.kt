package com.riffle.core.domain

import com.riffle.core.models.AudiobookTrackSpan

/**
 * A ready-to-play [Audiobook] session resolved from ABS (ADR 0035): the ordered, directly-streamable
 * track URLs paired with their timeline [tracks], the [timeline] (duration + chapter markers), and the
 * server-recorded resume position. Audio streams from ABS; nothing here comes from a Storyteller bundle.
 *
 * [localZipFilePath] is the absolute filesystem path to a bundle zip file for bundle-backed audio
 * (e.g. a downloaded Storyteller bundle), or null when tracks are HTTP/file URLs. Stored as a String
 * so this class lives in commonMain; the Android player converts it back to `java.io.File` internally.
 */
data class AudiobookSession(
    val trackUrls: List<String>,
    val tracks: List<AudiobookTrackSpan>,
    val timeline: AudiobookTimeline,
    val serverCurrentTimeSec: Double,
    // ABS's server-side `lastUpdate` (ms) for this item's media-progress record, for last-update-wins
    // resume against the durable local store. 0 when unknown (offline / downloaded session).
    val serverLastUpdate: Long = 0,
    // Absolute path to the local zip archive backing zip-entry track URLs (a downloaded bundle), or null
    // when tracks are HTTP/file URLs. The player points the playback service at this file before preparing.
    val localZipFilePath: String? = null,
    // Direct-download URLs for each track (same order as trackUrls), or null when trackUrls are
    // also byte-downloadable. Sources whose streaming format cannot be byte-downloaded (e.g. O'Reilly
    // HLS) populate this; download/cache paths use these instead of trackUrls.
    val downloadTrackUrls: List<String>? = null,
)

interface AudiobookRepository {
    /** Opens an ABS direct-play session for the audiobook item, or null if it can't be opened. */
    suspend fun openSession(sourceId: String, itemId: String): AudiobookSession?

    /** Whole-audiobook byte size for determinate offline-download progress, or null when unknown. */
    suspend fun downloadSizeBytes(sourceId: String, itemId: String): Long? = null

    /**
     * Pushes the audiobook's book-absolute listen position to its single ABS progress record. This is
     * the audiobook-only single-peer sync (ADR 0035); a matched Readaloud routes through the canonical
     * reconciliation cycle instead. No-op-safe to call repeatedly (on pause / close / periodically).
     */
    suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double)
}
