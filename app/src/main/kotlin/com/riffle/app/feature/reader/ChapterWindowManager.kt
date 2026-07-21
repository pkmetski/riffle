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
internal class ChapterWindowManager(chaptersBehind: Int) {

    /**
     * Number of chapters kept behind the viewport midpoint before a forward shift fires. Also the
     * threshold `viewportChapterIndex − topIndex > chaptersBehind` uses. Mutable so the elided
     * (Highlights-mode) reader can raise it (short synthetic chapters make the "gap≥2 → oscillate"
     * pattern common — see [ContinuousPositionTrackerTest] "backward→forward oscillation"), while
     * full-book continuous mode keeps the lower value that fits its per-chapter memory budget.
     * Callers must set this BEFORE `openWindowAt` — changing it mid-window doesn't resize the
     * loaded slots.
     */
    var chaptersBehind: Int = chaptersBehind

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
        viewportHeight: Int = 0,
    ): Decision {
        if (window.isEmpty()) return Decision.Hold

        val firstChapterHeight = window.first().height

        // Consume the guard before evaluating so it clears regardless of which branch fires.
        val skipBackward = justShiftedForward
        justShiftedForward = false

        val shouldShiftBackward = !skipBackward
            && scrollY < firstChapterHeight / 2
            && topIndex > 0

        // Bottom-of-window signal: scroll is clamped at the end of the loaded content. Callers
        // that don't pass `viewportHeight` (default 0) get `false` here — the midpoint trigger
        // then governs alone, preserving prior behavior for tests and any legacy call sites.
        //
        // Guard: the loaded window must EXCEED the viewport for "bottom" to mean the user
        // scrolled to reach it. When all loaded chapters fit in one viewport (`loadedContentBottom
        // <= viewportHeight`), scrollY is pinned at 0 and the bottom is visible without any scroll
        // — firing here would auto-cascade forward shifts on the very first `decide` after open,
        // dragging the user past the chapter they opened at and blocking backward nav. This
        // regressed the initial fix (2026-07-21 log: window jumped [ch5,ch6,ch7]→[ch8,ch9,ch10]
        // in ~1 s with no user scroll input).
        val loadedContentBottom = window.last().let { it.top + it.height }
        val atBottomOfLoadedWindow = viewportHeight > 0 &&
            loadedContentBottom > viewportHeight &&
            scrollY + viewportHeight >= loadedContentBottom

        val shouldShiftForward = ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = viewportChapterIndex,
            topIndex = topIndex,
            loadedChapterCount = window.size,
            readingOrderSize = totalChapters,
            chaptersBehind = chaptersBehind,
            atBottomOfLoadedWindow = atBottomOfLoadedWindow,
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
