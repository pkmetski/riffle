package com.riffle.feature.reader

/**
 * State machine for the "parked-on-sentence" invariant (ADR 0037).
 *
 * When readaloud pauses or closes we remember the sentence it stopped on AND the reader page it
 * sits on. While parked, the reconcile cycle inbound-mirrors the audiobook (never overwrites the
 * precise sentence position with a page-top derivation). Any navigation off that page clears the
 * park so the outbound-write behaviour resumes.
 *
 * Pure state — no coroutines, no I/O, no platform types.
 */
internal class ReadaloudParkPolicy {

    /** The fragment ref readaloud stopped on, or null when not parked. */
    var fragmentRef: String? = null
        private set

    private var locatorHref: String? = null
    private var progression: Double? = null

    fun onPause(pausedFragment: String?, snapshotHref: String?, snapshotProgression: Double?) {
        if (pausedFragment == null) return
        fragmentRef = pausedFragment
        locatorHref = snapshotHref
        progression = snapshotProgression
    }

    fun onClose(resumeFragment: String?, snapshotHref: String?, snapshotProgression: Double?) {
        fragmentRef = resumeFragment
        locatorHref = snapshotHref
        progression = snapshotProgression
    }

    fun onPosition(newHref: String, newProgression: Double?) {
        if (fragmentRef == null) return
        val movedOffPage = newHref != locatorHref ||
            kotlin.math.abs((newProgression ?: 0.0) - (progression ?: 0.0)) > PARK_PAGE_EPS
        if (movedOffPage) reset()
    }

    fun reset() {
        fragmentRef = null
        locatorHref = null
        progression = null
    }

    companion object {
        internal const val PARK_PAGE_EPS = 0.001
    }
}
