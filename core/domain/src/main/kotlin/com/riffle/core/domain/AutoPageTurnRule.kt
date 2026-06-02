package com.riffle.core.domain

/**
 * Decides whether playback has carried the active sentence off the bottom of the viewport,
 * meaning the reader should advance to keep the narrated text on screen. Pure: the caller
 * supplies the active clip's document index and the indices currently rendered; the reader
 * decides *how* to advance (page turn when paginated, scroll when continuous).
 */
object AutoPageTurnRule {

    fun shouldAdvance(
        activeIndex: Int?,
        visibleIndices: Set<Int>,
        isPlaying: Boolean,
    ): Boolean {
        if (!isPlaying) return false
        if (activeIndex == null) return false
        if (visibleIndices.isEmpty()) return false
        // Advance only when the active sentence is *ahead* of everything on screen. If it's
        // behind (the user scrolled forward manually while audio lags), leave the view alone.
        return activeIndex > visibleIndices.max()
    }
}
