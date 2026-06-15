package com.riffle.app.feature.reader

internal object ContinuousPositionTracker {

    data class ChapterSlot(val href: String, val top: Int, val height: Int)

    enum class ShiftDirection { NONE, FORWARD, BACKWARD }

    /**
     * Returns the chapter href and within-chapter progression (0..1) at the viewport midpoint.
     * Falls back to the last slot if [scrollY] is past all content.
     */
    fun locatorAt(scrollY: Int, viewportHeight: Int, window: List<ChapterSlot>): Pair<String, Float> {
        require(window.isNotEmpty()) { "ChapterSlot window must not be empty" }
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
     * Indicates whether the window (3 chapters: [topIndex, topIndex+2]) needs a FORWARD shift.
     *
     * FORWARD fires when the viewport **bottom** edge enters the last chapter ([topIndex+2]).
     * Using the viewport bottom (not the midpoint) is critical for short chapters: if the last
     * chapter is shorter than half the viewport height, the midpoint never enters it and the
     * shift would never fire.
     *
     * BACKWARD is NOT handled here — it is checked at the call site via a scrollY threshold
     * (`scrollY < firstChapterHeight / 2`). A chapter-index-based backward condition would
     * immediately re-trigger after every FORWARD shift (the forward scrollBy adjustment always
     * lands in the new first chapter), causing an infinite oscillation.
     */
    fun forwardShiftNeeded(
        viewportBottomChapterIndex: Int,
        topIndex: Int,
        readingOrderSize: Int,
    ): Boolean = viewportBottomChapterIndex > topIndex + 1 && topIndex + 3 < readingOrderSize
}
