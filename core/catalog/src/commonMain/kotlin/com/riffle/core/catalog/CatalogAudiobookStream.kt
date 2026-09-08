package com.riffle.core.catalog

/**
 * A playable audiobook stream: track URLs (auth baked in), chapter markers, current server-side
 * position, and the last-update timestamp for last-writer-wins reconciliation (ADR 0035).
 */
data class CatalogAudiobookStream(
    val trackUrls: List<String>,
    val tracks: List<CatalogAudioTrack>,
    val chapters: List<CatalogAudiobookChapter>,
    val totalDurationSec: Double,
    val serverCurrentTimeSec: Double,
    val serverLastUpdate: Long,
    /**
     * Direct-download URLs for each track (same ordering as [trackUrls]), or null when the stream
     * URLs are also suitable for byte-download. Set for sources whose streaming format (e.g. HLS
     * `.m3u8`) cannot be byte-downloaded directly — callers use these for offline download/cache and
     * fall back to [trackUrls] when null.
     */
    val downloadTrackUrls: List<String>? = null,
)

data class CatalogAudiobookChapter(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)
