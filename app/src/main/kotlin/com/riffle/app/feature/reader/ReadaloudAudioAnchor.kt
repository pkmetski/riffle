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
     * @param parkedFragment the sentence readaloud last **stopped** on (close/pause), retained until the
     *   user navigates to a different page. When set, the reader is still parked on that exact sentence,
     *   so reading→audiobook uses it — NOT the page-top, which would regress the audiobook below where
     *   readaloud stopped (the post-close clobber; ADR 0031). The page-top is only for *genuine* silent
     *   reading where no sentence is known.
     * @param fragmentSeconds resolves a fragment ref to its absolute audio second (bundle SMIL). Only
     *   ever called for a non-null fragment.
     * @param pageSeconds the page/canonical-derived second (page-top sentence). Only consulted for
     *   silent reading with no known sentence; never evaluated while readaloud is open or parked.
     */
    inline fun audiobookSeconds(
        activeFragment: String?,
        readaloudOpen: Boolean,
        parkedFragment: String? = null,
        fragmentSeconds: (String) -> Double?,
        pageSeconds: () -> Double?,
    ): Double? = when {
        activeFragment != null -> fragmentSeconds(activeFragment) // live narration: sentence-sharp
        readaloudOpen -> null                                     // race: fragment not ready — skip
        parkedFragment != null -> fragmentSeconds(parkedFragment) // parked on the stop sentence: precise
        else -> pageSeconds()                                     // silent reading: page-top is fine
    }
}
