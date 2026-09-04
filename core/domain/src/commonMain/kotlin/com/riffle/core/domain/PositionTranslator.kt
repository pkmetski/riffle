package com.riffle.core.domain

/**
 * Single point of conversion between position coordinate systems for a matched book
 * (ADR 0023, amended by ADRs 0026 and 0029). Platform-neutral; the default implementation
 * ([DefaultPositionTranslator]) lives in jvmMain (uses Jsoup-backed CFI functions).
 *
 * Every conversion is best-effort: an input that cannot be placed in the target system
 * returns `null` rather than guessing, so a missing mapping degrades a sync cycle to a
 * deferred PATCH instead of a wrong one.
 */
interface PositionTranslator {

    // ── SMIL-only (work without spine/HTML/cross-EPUB index) ───────────────────

    /** Audio time the narrated fragment (`href#id`) begins. `null` for an unknown fragment. */
    fun fragmentRefToAudioSeconds(textFragmentRef: String): Double?

    /** Audio time → narrated Storyteller text fragment. `null` when no clip covers the time. */
    fun audioSecondsToTextFragment(seconds: Double): String?

    /** Audio time → canonical (Storyteller-EPUB) progression. */
    fun audioSecondsToStorytellerProgression(seconds: Double): ChapterProgression?

    /** Canonical (Storyteller-EPUB) progression → audio time at the latest narrated
     *  fragment at or before [pos] within the same chapter. */
    fun storytellerProgressionToAudioSeconds(pos: ChapterProgression): Double?

    /** The narrated fragment at or before [pos] within the same chapter. */
    fun fragmentAt(pos: ChapterProgression): String?

    // ── Cross-EPUB progression (require cross-EPUB index) ──────────────────────

    fun storytellerToAbsProgression(pos: ChapterProgression): ChapterProgression?
    fun absToStorytellerProgression(pos: ChapterProgression): ChapterProgression?
    fun absBookProgression(pos: ChapterProgression): Double?

    // ── Canonical (displayed-EPUB Locator JSON) seam ───────────────────────────

    /** ABS `epubcfi(...)` → canonical Locator JSON on the displayed (ABS) EPUB. */
    fun absCfiToCanonical(cfi: String): String?

    /** Canonical Locator JSON → ABS `epubcfi(...)`. */
    fun canonicalToAbsCfi(locatorJson: String): String?

    /** Book-wide progress (0..1) for the ABS progress bar — uses the locator's
     *  `totalProgression` when present, else weights chapters by character count. */
    fun canonicalBookProgress(locatorJson: String): Float

    /** Storyteller-EPUB Locator JSON → canonical (displayed-EPUB) Locator JSON. */
    fun storytellerLocatorToCanonical(stLocatorJson: String): String?

    /** Canonical → Storyteller-EPUB Locator JSON. */
    fun canonicalToStorytellerLocator(locatorJson: String): String?

    /** Audio second → canonical Locator JSON on the displayed (ABS) EPUB. */
    fun audioSecondsToCanonical(seconds: Double): String?

    /** Canonical Locator JSON → audio second. */
    fun canonicalToAudioSeconds(locatorJson: String): Double?

    /** Exact audio time a narrated fragment begins (sentence-precise). */
    fun audioSecondsForFragment(textFragmentRef: String): Double?

    /** Narrated Storyteller fragment an audio time falls in. */
    fun fragmentForAudioSeconds(seconds: Double): String?

    /** Narrated Storyteller fragment a canonical reading position falls in. */
    fun canonicalToFragmentRef(locatorJson: String): String?

    /** Storyteller bundle spine href for a displayed (ABS) href — spine-aligned by index. */
    fun displayedHrefToBundleHref(displayedHref: String): String?
}
