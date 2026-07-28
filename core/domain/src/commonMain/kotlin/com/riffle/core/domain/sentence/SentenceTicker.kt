package com.riffle.core.domain.sentence

import kotlinx.coroutines.flow.StateFlow

/**
 * Drives highlighted sentence playback, shared between Readaloud (audio-driven SMIL ticking)
 * and Cadence (timer-based WPM simulation).
 *
 * Two main consumer flows:
 * - Readaloud: ExoPlayer position → SMIL-resolved clip → [currentFragment] + [progress]
 * - Cadence: WPM timer → dwellPerSentence → [currentFragment] + [progress]
 */
interface SentenceTicker {
    /** Fragment currently under the highlight; null when idle/stopped. */
    val currentFragment: StateFlow<FragmentRef?>

    /**
     * Fractional progress within the current fragment [0,1] — used by paginated mode
     * for mid-sentence page turns. Readaloud gets this from clip.progressAt();
     * Cadence approximates as elapsedInSentence / dwellForSentence.
     */
    val progress: StateFlow<Double?>

    fun play()
    fun pause()
    fun stop()

    /** Jump the current fragment; ticker begins ticking from there on next play. */
    fun goTo(fragment: FragmentRef)
}
