package com.riffle.app.feature.reader

internal object ContinuousPositionTracker {

    data class ChapterSlot(val href: String, val top: Int, val height: Int)

    enum class ShiftDirection { NONE, FORWARD, BACKWARD }

    /**
     * Returns the chapter href and within-chapter progression (0..1) at the viewport midpoint.
     * Falls back to the last slot if [scrollY] is past all content.
     */
    fun locatorAt(scrollY: Int, viewportHeight: Int, window: List<ChapterSlot>): Pair<String, Float> {
        val midY = scrollY + viewportHeight / 2
        val slot = window.lastOrNull { midY >= it.top } ?: window.first()
        val progression = if (slot.height > 0) {
            ((midY - slot.top).toFloat() / slot.height).coerceIn(0f, 1f)
        } else {
            0f
        }
        return slot.href to progression
    }

    /**
     * Returns the content offset (from the top of the scroll view) for a given
     * chapter + progression. Returns null if [href] is not in [window].
     */
    fun scrollOffsetFor(href: String, progression: Float, window: List<ChapterSlot>): Int? {
        val slot = window.firstOrNull { it.href == href } ?: return null
        return (slot.top + progression * slot.height).toInt()
    }

    /**
     * Indicates whether the window (3 chapters: [topIndex, topIndex+2]) needs to shift
     * because [currentChapterIndex] has moved outside it.
     */
    fun shiftNeeded(currentChapterIndex: Int, topIndex: Int, readingOrderSize: Int): ShiftDirection {
        return when {
            currentChapterIndex < topIndex && topIndex > 0 -> ShiftDirection.BACKWARD
            currentChapterIndex > topIndex + 2 && topIndex + 3 < readingOrderSize -> ShiftDirection.FORWARD
            else -> ShiftDirection.NONE
        }
    }
}
