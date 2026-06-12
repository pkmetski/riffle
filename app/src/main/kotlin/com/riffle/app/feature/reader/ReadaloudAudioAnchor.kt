package com.riffle.app.feature.reader

/**
 * Decides which audio second the matched book's audiobook position should be written from, given the
 * live readaloud/reading state (ADR 0031). The bundle SMIL **fragment** is the pivot; the page
 * canonical is only ever a fallback for genuine silent reading — and **never** while a readaloud
 * session is active (a transient null fragment there is the page-top race that synced the audiobook
 * ~a minute early).
 *
 * Pure and side-effect-free (the seconds sources are passed as lambdas), so the anchoring rule is
 * unit-testable without the reader, player, or bundle. Returns `null` when nothing should be written.
 */
object ReadaloudAudioAnchor {

    /**
     * @param activeFragment the sentence currently narrated (`null` between clips / before start).
     * @param readaloudOpen whether a readaloud session is active. When `true`, a null [activeFragment]
     *   is transient and must NOT fall back to the page — we skip rather than write a page-top position.
     * @param fragmentSeconds resolves a fragment ref to its absolute audio second (bundle SMIL). Only
     *   ever called for a non-null fragment.
     * @param pageSeconds the page/canonical-derived second (page-top sentence). Only consulted for
     *   silent reading (no active readaloud); never evaluated while readaloud is open.
     */
    inline fun audiobookSeconds(
        activeFragment: String?,
        readaloudOpen: Boolean,
        fragmentSeconds: (String) -> Double?,
        pageSeconds: () -> Double?,
    ): Double? = when {
        activeFragment != null -> fragmentSeconds(activeFragment) // sentence-sharp; no page fallback
        readaloudOpen -> null                                     // race: fragment not ready — skip
        else -> pageSeconds()                                     // silent reading: page-top is fine
    }
}
