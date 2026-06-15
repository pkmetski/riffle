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
     * Indicates whether the loaded window needs a FORWARD shift to keep enough chapters
     * buffered ahead of the reader.
     *
     * Trigger is **look-ahead based**, not "you can see the last slot": FORWARD fires as soon as
     * the chapter at the viewport **midpoint** has advanced more than [chaptersBehind] slots past
     * the top of the window. Each shift drops the topmost chapter and appends one at the bottom,
     * so after a shift the midpoint chapter sits exactly [chaptersBehind] slots from the top again
     * and the condition clears — no oscillation.
     *
     * Why midpoint, not the viewport bottom: the previous bottom-edge trigger only fired once the
     * reader could already *see* the final loaded slot, giving the next chapter zero time to load.
     * With short "CHAPTER N" divider pages each eating a whole slot, the real content chapter then
     * started loading exactly when the reader reached it — producing the blank gap + spinner + jump
     * at every chapter boundary. Triggering on the midpoint keeps several chapters loaded *beyond*
     * the reader so the next one is already rendered and measured before they arrive.
     *
     * BACKWARD is NOT handled here — it is checked at the call site via a scrollY threshold
     * (`scrollY < firstChapterHeight / 2`). A chapter-index-based backward condition would
     * immediately re-trigger after every FORWARD shift (the forward scrollBy adjustment always
     * lands in the new first chapter), causing an infinite oscillation.
     */
    fun forwardShiftNeeded(
        viewportChapterIndex: Int,
        topIndex: Int,
        loadedChapterCount: Int,
        readingOrderSize: Int,
        chaptersBehind: Int,
    ): Boolean {
        val moreChaptersExist = topIndex + loadedChapterCount < readingOrderSize
        val pastBehindBudget = viewportChapterIndex - topIndex > chaptersBehind
        return moreChaptersExist && pastBehindBudget
    }
}
