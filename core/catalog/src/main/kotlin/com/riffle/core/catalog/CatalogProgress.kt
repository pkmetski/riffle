package com.riffle.core.catalog

/**
 * A progress record returned by a [ProgressPeerCapability] peer. Ebook and audiobook progress
 * share the same envelope so callers can reconcile against one stream.
 *
 * [finishedAt] carries the "when did this book finish" timestamp so the library UI's "Finished"
 * badge stays server-accurate; [isFinished] is the derived boolean.
 */
data class CatalogProgress(
    val itemId: String,
    val ebookLocation: String? = null,
    val ebookProgress: Float = 0f,
    val audioCurrentTime: Double = 0.0,
    val audioDuration: Double = 0.0,
    val isFinished: Boolean = false,
    val finishedAt: Long? = null,
    val lastUpdate: Long,
)
