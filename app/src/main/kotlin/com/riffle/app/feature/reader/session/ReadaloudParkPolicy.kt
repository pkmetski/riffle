package com.riffle.app.feature.reader.session

/**
 * State machine for the "parked-on-sentence" invariant (ADR 0031).
 *
 * When readaloud pauses or closes we remember the sentence it stopped on AND the reader page it
 * sits on. While parked, the reconcile cycle inbound-mirrors the audiobook (never overwrites the
 * precise sentence position with a page-top derivation). Any navigation off that page clears the
 * park so the outbound-write behaviour resumes.
 *
 * Extracted from ReadaloudSession as part of #380. Pure state — no coroutines, no I/O, no
 * Readium types.
 */
internal class ReadaloudParkPolicy {

    /** The fragment ref readaloud stopped on, or null when not parked. */
    var fragmentRef: String? = null
        private set

    private var locatorHref: String? = null
    private var progression: Double? = null

    /** Records the park after a pause. No-op when [pausedFragment] is null. */
    fun onPause(pausedFragment: String?, snapshotHref: String?, snapshotProgression: Double?) {
        if (pausedFragment == null) return
        fragmentRef = pausedFragment
        locatorHref = snapshotHref
        progression = snapshotProgression
    }

    /**
     * Records the park after a close. Unlike [onPause] this always writes: closing intentionally
     * stamps the park even when there is no active fragment, so the next open can decide whether
     * to resume in place.
     */
    fun onClose(resumeFragment: String?, snapshotHref: String?, snapshotProgression: Double?) {
        fragmentRef = resumeFragment
        locatorHref = snapshotHref
        progression = snapshotProgression
    }

    /**
     * Handles a reader-position update. Clears the park when the reader has navigated off the
     * parked page. When not parked, does nothing.
     */
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
        /**
         * A reading-position progression change beyond this (or any href change) counts as
         * navigating off the parked page; smaller deltas are settle jitter on the same page
         * (ADR 0031).
         */
        internal const val PARK_PAGE_EPS = 0.001
    }
}
