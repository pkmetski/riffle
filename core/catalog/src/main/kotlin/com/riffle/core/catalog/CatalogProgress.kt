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
) {
    /**
     * The single "how far through this item" fraction that `library_items.readingProgress`
     * stores (ADR 0035). Every writer of that column that consumes a [CatalogProgress] must
     * derive through here — the bulk pull ([ProgressPeerCapability.pullAllProgress]) and the
     * per-item pull ([ProgressPeerCapability.pullProgress]) hit different endpoints, and letting
     * them derive differently makes the two writers ping-pong the library UI between two values.
     *
     * `isFinished` pins to 1f to cover "server marks finished but audioCurrentTime slightly
     * under duration". A real (>0) ebook fraction wins over the audio position (ABS ships
     * `ebookProgress = 0` on audio-only items). Returns null when the payload carries no
     * meaningful progress at all (fresh item / audio-only book whose duration hasn't populated
     * server-side yet) — writing 0 in that case would clobber a previously-adopted value.
     */
    fun unifiedLibraryFraction(): Float? = when {
        isFinished -> 1f
        ebookProgress > 0f -> ebookProgress.coerceIn(0f, 1f)
        audioDuration > 0.0 -> (audioCurrentTime / audioDuration).toFloat().coerceIn(0f, 1f)
        else -> null
    }
}
