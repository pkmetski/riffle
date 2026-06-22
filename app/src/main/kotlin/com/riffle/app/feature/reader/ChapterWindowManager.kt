package com.riffle.app.feature.reader

/**
 * Pure algorithm for deciding when the sliding chapter window should shift.
 *
 * [ContinuousReaderView] owns the Android View mechanics — scroll, WebView lifecycle, scroll
 * compensation on shift. This class owns only the *decision*: given the current scroll state,
 * should the window shift forward, backward, or hold?
 *
 * Extracted so the oscillation-prone algorithm is unit-testable without a running View.
 *
 * Stateful: tracks the [justShiftedForward] guard internally so callers do not have to thread it.
 * Create one instance per [ContinuousReaderView]; call [reset] when the window is rebuilt.
 */
internal class ChapterWindowManager(private val chaptersBehind: Int) {

    sealed class Decision {
        data object Hold : Decision()
        data object ShiftForward : Decision()
        data object ShiftBackward : Decision()
    }

    /**
     * Set after a forward shift so the very next [decide] cycle suppresses the backward-shift
     * check. Without this guard, a short first chapter (shorter than the viewport) triggers an
     * immediate backward shift in the next cycle after every forward shift:
     *
     *  - Forward shift fires (midpoint crosses into ch[N+1]).
     *  - removeTop compensates: scrollY -= ch[N-1].height → new scrollY lands near the top of
     *    the new first chapter.
     *  - When ch[N] is shorter than viewport/2, new scrollY < firstChapterHeight/2 → backward
     *    shift fires immediately → oscillation.
     *
     * Suppressing for ONE cycle absorbs the compensating scroll without affecting deliberate
     * backward navigation: the very next cycle is triggered by real user input (fling or gesture),
     * at which point scrollY has moved past the threshold or the user genuinely scrolled back.
     */
    private var justShiftedForward = false

    /**
     * Decide whether the window should shift given the current scroll state.
     *
     * [viewportChapterIndex] is the global reading-order index of the chapter at the viewport
     * midpoint — compute it from [ContinuousPositionTracker.locatorAt]. A value of -1 (not found)
     * is safe: it makes [forwardShiftNeeded] return false.
     *
     * At most one shift per call by design: scroll compensation uses the stored height of the
     * removed chapter, which is only accurate before removal. Chasing multiple shifts in one pass
     * risks removing un-measured chapters and opening a blank gap.
     */
    fun decide(
        scrollY: Int,
        viewportChapterIndex: Int,
        window: List<ContinuousPositionTracker.ChapterSlot>,
        topIndex: Int,
        totalChapters: Int,
    ): Decision {
        if (window.isEmpty()) return Decision.Hold

        val firstChapterHeight = window.first().height

        // Consume the guard before evaluating so it clears regardless of which branch fires.
        val skipBackward = justShiftedForward
        justShiftedForward = false

        val shouldShiftBackward = !skipBackward
            && scrollY < firstChapterHeight / 2
            && topIndex > 0

        val shouldShiftForward = ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = viewportChapterIndex,
            topIndex = topIndex,
            loadedChapterCount = window.size,
            readingOrderSize = totalChapters,
            chaptersBehind = chaptersBehind,
        )

        return when {
            shouldShiftBackward -> Decision.ShiftBackward
            shouldShiftForward -> {
                justShiftedForward = true
                Decision.ShiftForward
            }
            else -> Decision.Hold
        }
    }

    /**
     * Clear guard state. Call whenever the window is rebuilt from scratch (navigation jump,
     * renderer recovery) so a stale guard from the previous position does not suppress the first
     * legitimate backward-shift check at the new location.
     */
    fun reset() {
        justShiftedForward = false
    }
}
