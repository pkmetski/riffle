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
     * under duration". When both ebook and audio data are present, the larger fraction wins —
     * ABS's bulk `/api/me/progress` endpoint can return a stale small `ebookProgress` (e.g.
     * 0.002) for audiobooks alongside the real audio position, so ebook-priority-first would
     * return the wrong value. Returns null when the payload carries no meaningful progress at
     * all (fresh item / audio-only book whose duration hasn't populated server-side yet) —
     * writing 0 in that case would clobber a previously-adopted value.
     */
    fun unifiedLibraryFraction(): Float? {
        if (isFinished) return 1f
        // Require audioCurrentTime > 0 so that a reset position (currentTime=0 after mark-as-unread
        // pushes 0 to the server) is indistinguishable from "never started" and treated as null.
        // Without this gate, the post-loop would write 0.0f to library_items.readingProgress whenever
        // the bulk endpoint returns currentTime=0 with a known duration, overwriting any valid DB value
        // that a concurrent per-item pull (refreshItemProgress) had just written.
        val audioFrac = if (audioDuration > 0.0 && audioCurrentTime > 0.0) (audioCurrentTime / audioDuration).toFloat().coerceIn(0f, 1f) else null
        val ebookFrac = if (ebookProgress > 0f) ebookProgress.coerceIn(0f, 1f) else null
        return when {
            audioFrac != null && ebookFrac != null -> maxOf(audioFrac, ebookFrac)
            else -> audioFrac ?: ebookFrac
        }
    }

    companion object {
        /**
         * The single position-derived "finished" for ABS progress, shared by the per-item pull
         * ([ProgressPeerCapability.pullProgress] → `toCatalogProgress`) and the bulk pull
         * ([ProgressPeerCapability.pullAllProgress]). Both endpoints must answer identically or the
         * detail screen and library card disagree on Finished state (and, via [unifiedLibraryFraction]
         * pinning finished to 1f, on the progress bar itself).
         *
         * A book is finished when EITHER dimension is complete (ebook 100% OR audio at/past duration) —
         * this matches [unifiedLibraryFraction]'s `maxOf`, so a matched book listened to the end but
         * read only 60% is finished, not stuck at 60%. ABS's sticky `isFinished`/`finishedAt` flags are
         * consulted only when there is no position data at all (marked finished without any reading),
         * because ABS does not auto-clear them when another device advances the position.
         */
        fun deriveIsFinished(
            ebookProgress: Float,
            audioCurrentTime: Double,
            audioDuration: Double,
            stickyFinished: Boolean = false,
            stickyFinishedAt: Long? = null,
        ): Boolean {
            val ebookFull = ebookProgress >= 1f
            val audioFull = audioDuration > 0.0 && audioCurrentTime > 0.0 && audioCurrentTime >= audioDuration
            val hasPosition = ebookProgress > 0f || (audioDuration > 0.0 && audioCurrentTime > 0.0)
            return when {
                ebookFull || audioFull -> true
                hasPosition -> false
                else -> stickyFinished || stickyFinishedAt != null
            }
        }
    }
}
