package com.riffle.core.domain

/**
 * One `<par>` of an EPUB 3 Media Overlay (SMIL): a text fragment paired with the
 * slice of audio that narrates it. [clipBeginSec]/[clipEndSec] are normalised to
 * absolute seconds from any SMIL clock-value syntax.
 */
data class MediaOverlayClip(
    val textFragmentRef: String,
    val audioSrc: String,
    val clipBeginSec: Double,
    val clipEndSec: Double,
) {
    /**
     * How far this clip's audio has elapsed at [positionSec], as a fraction in `[0, 1]`. Since
     * read-aloud timing is per-sentence (one clip per sentence), this is the only within-sentence
     * progress signal available — the reader uses it to turn the page when a sentence spans more than
     * one paginated column. A zero-or-negative-duration clip (a degenerate SMIL `par`) reports 0.
     */
    fun progressAt(positionSec: Double): Double {
        val duration = clipEndSec - clipBeginSec
        if (duration <= 0.0) return 0.0
        return ((positionSec - clipBeginSec) / duration).coerceIn(0.0, 1.0)
    }
}
