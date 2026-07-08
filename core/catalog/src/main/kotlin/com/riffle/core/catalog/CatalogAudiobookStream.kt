package com.riffle.core.catalog

/**
 * A playable audiobook stream: track URLs (auth baked in), chapter markers, current server-side
 * position, and the last-update timestamp for last-writer-wins reconciliation (ADR 0029).
 */
data class CatalogAudiobookStream(
    val trackUrls: List<String>,
    val tracks: List<CatalogAudioTrack>,
    val chapters: List<CatalogAudiobookChapter>,
    val totalDurationSec: Double,
    val serverCurrentTimeSec: Double,
    val serverLastUpdate: Long,
)

data class CatalogAudiobookChapter(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)
