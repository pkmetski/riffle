package com.riffle.feature.player

/**
 * Platform-agnostic seam for the readaloud controller's pre-warm seek functionality.
 *
 * Android implementation: [com.riffle.app.feature.reader.readaloud.ReadaloudController].
 *
 * Hides all Android/Media3 types from [AudiobookPlayerViewModel].
 */
interface ReadaloudHandoff {
    /**
     * Pre-resolves the SMIL seek target so [playFromSecond] in the readaloud controller can skip
     * the computation at commit time (ADR 0039). Called when the user starts dragging down (before
     * the threshold). No-op when no readaloud track is loaded.
     */
    fun preWarmSeek(globalSec: Double)

    /** Discards the pre-warm if the drag was abandoned without crossing the threshold. */
    fun cancelPreWarm()
}
