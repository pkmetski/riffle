package com.riffle.core.catalog

/**
 * A progress record returned by a [ProgressPeerCapability] peer. Ebook and audiobook progress
 * share the same envelope so callers can reconcile against one stream.
 */
data class CatalogProgress(
    val itemId: String,
    val ebookLocation: String? = null,
    val ebookProgress: Float = 0f,
    val audioCurrentTime: Double = 0.0,
    val audioDuration: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long,
)
