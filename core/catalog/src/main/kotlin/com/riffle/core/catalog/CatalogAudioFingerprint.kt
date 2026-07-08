package com.riffle.core.catalog

/**
 * A stable identity for an item's audio content. Used by cross-Source audio identity resolution
 * (matching a Storyteller bundle to an ABS audiobook, for instance). Shape mirrors ABS's audio
 * fingerprint payload; other backends fill what they can (LocalFiles reports the concatenated file
 * size, streaming-only backends may report 0 and rely on duration alone).
 */
data class CatalogAudioFingerprint(
    val itemId: String,
    val fileSizeBytes: Long,
    val totalDurationSec: Double,
    val trackDurations: List<Double>,
)
