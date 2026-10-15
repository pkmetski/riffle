package com.riffle.feature.reader

/**
 * Decides which audio second the matched book's audiobook position should be written from, given the
 * live readaloud/reading state (ADR 0037). The bundle SMIL **fragment** is the pivot; the page
 * canonical is only ever a fallback for genuine silent reading — and **never** while a readaloud
 * session is active (a transient null fragment there is the page-top race that synced the audiobook
 * ~a minute early).
 *
 * Pure and side-effect-free, so the anchoring rule is unit-testable without the reader or bundle.
 * Returns `null` when nothing should be written.
 */
object ReadaloudAudioAnchor {

    /**
     * @param activeFragment the sentence currently narrated (`null` between clips / before start).
     * @param readaloudOpen whether a readaloud session is active. When `true`, a null [activeFragment]
     *   is transient and must NOT fall back to the page — we skip rather than write a page-top position.
     * @param parkedFragment the sentence readaloud last **stopped** on (close/pause), retained until the
     *   user navigates to a different page. When set, reading→audiobook uses it — NOT the page-top.
     * @param fragmentSeconds resolves a fragment ref to its absolute audio second (bundle SMIL).
     * @param pageSeconds the page/canonical-derived second. Only consulted for silent reading.
     */
    inline fun audiobookSeconds(
        activeFragment: String?,
        readaloudOpen: Boolean,
        parkedFragment: String? = null,
        fragmentSeconds: (String) -> Double?,
        pageSeconds: () -> Double?,
    ): Double? = when {
        activeFragment != null -> fragmentSeconds(activeFragment)
        readaloudOpen -> null
        parkedFragment != null -> fragmentSeconds(parkedFragment)
        else -> pageSeconds()
    }
}
