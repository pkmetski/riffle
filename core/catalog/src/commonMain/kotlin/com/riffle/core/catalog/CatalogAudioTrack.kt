package com.riffle.core.catalog

data class CatalogAudioTrack(
    val ino: String,
    val index: Int,
    val startOffsetSec: Double,
    val durationSec: Double,
    val contentUrl: String,
    val mimeType: String? = null,
)
