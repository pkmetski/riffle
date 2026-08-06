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

        /**
         * Grow the loaded window by appending the next chapter WITHOUT dropping the top. Fires in
         * two situations:
         *
         * 1. **Fits-in-viewport**: every currently-loaded chapter fits inside a single viewport.
         *    Field case: front-matter with several tiny files ("Praise for…" 2 KB + "Selected
         *    Works From…" 1 KB + "Title" 0.6 KB). All fit on-screen at once; ShiftForward never
         *    triggers because scrollY is pinned at 0 so pastBehindBudget is a false positive.
         *
         * 2. **At-bottom, window not full**: the viewport bottom already touches or exceeds the
         *    bottom of loaded content, but the window is under [appendOnlyMaxWindow]. Field case:
         *    the book opens at a mid-spine chapter (e.g. title page) whose chapter + its neighbours
         *    fit in or nearly fill the viewport, so after the initial scroll the user is immediately
         *    at the bottom of loaded content. Without this path, ShiftForward fires, drops the top
         *    chapter (fm02), and scroll compensation resets scrollY to 0 — which triggers
         *    ShiftBackward, which prepends fm02 again and the cycle oscillates indefinitely.
         *    AppendOnly extends forward without losing the opening chapter, breaking the loop.
         *
         * Unlike ShiftForward, this decision doesn't advance [topIndex] so the user can still scroll
         * back to the chapters they opened at. Bounded by [appendOnlyMaxWindow] in [decide] to
         * prevent a pathological all-short-chapters book from loading the whole spine at open.
         */
        data object AppendOnly : Decision()
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
        appendOnlyMaxWindow: Int = 0,
        backwardNavigationIntent: Boolean = false,
        topChapterStillPlaceholder: Boolean = false,
    ): Decision {
        if (window.isEmpty()) return Decision.Hold

        val firstChapterHeight = window.first().height

        // Consume the guard before evaluating so it clears regardless of which branch fires.
        val skipBackward = justShiftedForward
        justShiftedForward = false

        // Bottom-of-window signal: scroll is clamped at the end of the loaded content. Callers
        // that don't pass `viewportHeight` (default 0) get `false` here — the midpoint trigger
        // then governs alone, preserving prior behavior for tests and any legacy call sites.
        //
        // Guard: the loaded window must EXCEED the viewport for "bottom" to mean the user
        // scrolled to reach it. When all loaded chapters fit in one viewport (`loadedContentBottom
        // <= viewportHeight`), scrollY is pinned at 0 and the bottom is visible without any scroll
        // — firing ShiftForward here would auto-cascade forward shifts on the very first `decide`
        // after open, dragging the user past the chapter they opened at and blocking backward nav
        // (regressed the initial fix on 2026-07-21). The AppendOnly branch below handles the
        // fits-in-viewport case safely by growing the window without dropping the top.
        val loadedContentBottom = window.last().let { it.top + it.height }
        val fitsInViewport = viewportHeight > 0 && loadedContentBottom <= viewportHeight

        // Suppress backward shift when entire loaded content fits in one viewport. In that state
        // scrollY is pinned at 0 regardless of the user's intent — triggering ShiftBackward on
        // `scrollY < firstChapterHeight/2` is a false positive that removes the trailing chapter
        // and prepends behind content, then leaves the window still sub-viewport with no further
        // trigger to fire AppendOnly (no scroll events fire when maxScrollY==0).
        // A short first resource can be smaller than half the viewport. In that geometry the
        // viewport midpoint remains in the following chapter even at scrollY=0, so chapter
        // indices cannot tell a genuine backward gesture from a forward-shift compensation.
        // Require direction supplied by the controller: forward intent absorbs compensation,
        // while backward intent can prepend even when the scroll view is pinned at zero.
        // One unmeasured prepend at a time. A prepended chapter enters at a screen-sized blank
        // placeholder; on a slow device a large real chapter takes seconds to load and measure.
        // Each further backward pull during that window would prepend ANOTHER blank placeholder
        // (its scrollY threshold is met inside the placeholder), and a few quick pulls replace
        // every rendered chapter in the window with white placeholders — field-observed
        // 2026-08-04 as a solid white screen at the ch11/Part III boundary. Holding here until
        // the pending prepend measures keeps at most one blank screen above the reader; the
        // measure's compensating scroll change re-runs the decision immediately after.
        val shouldShiftBackward = !skipBackward
            && scrollY < firstChapterHeight / 2
            && topIndex > 0
            && !fitsInViewport
            && backwardNavigationIntent
            && !topChapterStillPlaceholder
        val atBottomOfLoadedWindow = viewportHeight > 0 &&
            loadedContentBottom > viewportHeight &&
            scrollY + viewportHeight >= loadedContentBottom

        // Suppress forward shift when all loaded content fits in one viewport. When fitsInViewport
        // is true, scrollY is pinned at 0 and the viewport midpoint lands arbitrarily deep in the
        // loaded window — pastBehindBudget fires even though no actual reading progress has been
        // made. ShiftForward would then remove the top chapter and cascade repeatedly, racing the
        // window far past the opened position (e.g. from title page to intro/ch01) before the user
        // touches anything. AppendOnly (lower priority) correctly handles the fits-in-viewport case
        // by growing the window without dropping the top. Both conditions are mutually exclusive with
        // loadedContentBottom > viewportHeight, so gating out ShiftForward here does not affect the
        // atBottomOfLoadedWindow trigger path.
        // Geometric guard on top-chapter eviction. The behind budget counts CHAPTERS, but a
        // sub-viewport part-title between the top chapter and the midpoint chapter makes the
        // index gap overshoot while the reader is still geometrically at the top chapter's
        // bottom edge. Right after a backward prepend the compensated scrollY equals exactly
        // firstChapterHeight; evicting the top chapter then clamps scrollY back to ~0, which
        // re-arms the backward trigger on the next gesture — an endless prepend/evict ping-pong
        // the user sees as a growing blank region (the prepended chapter is evicted before its
        // placeholder ever renders). Eviction is only legal when it leaves at least half a
        // viewport of scrollback behind the reader. Callers that don't pass viewportHeight
        // (legacy tests) keep the pure index-based behavior.
        //
        // The atBottomOfLoadedWindow trigger is exempt: at the bottom clamp the reader is
        // wedged against the end of loaded content and eviction is the only way to progress
        // (see the short-trailing-chapter wall-off tests) — and that state is geometrically
        // disjoint from the post-prepend state this guard protects (right after a prepend the
        // viewport sits near the TOP of the loaded window, viewports away from the bottom clamp).
        val evictionLeavesScrollback = viewportHeight <= 0 ||
            atBottomOfLoadedWindow ||
            scrollY - firstChapterHeight >= viewportHeight / 2
        val shouldShiftForward = !backwardNavigationIntent && evictionLeavesScrollback &&
            !fitsInViewport && ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = viewportChapterIndex,
            topIndex = topIndex,
            loadedChapterCount = window.size,
            readingOrderSize = totalChapters,
            chaptersBehind = chaptersBehind,
            atBottomOfLoadedWindow = atBottomOfLoadedWindow,
        )

        val moreChaptersExist = topIndex + window.size < totalChapters
        // AppendOnly is opt-in via [appendOnlyMaxWindow] > 0 so legacy call sites (all existing
        // tests) preserve their prior Hold behavior.
        //
        // Fires in two cases (see Decision.AppendOnly docstring):
        // 1. fitsInViewport: all loaded content fits in one viewport (scrollY pinned at 0).
        //    The window-size cap does NOT apply here: when all content fits in one screen there
        //    are no scroll events, so the only way to break the deadlock is to keep appending
        //    chapters until content spills past the viewport and the user can scroll. The cap
        //    is self-enforced naturally — AppendOnly stops as soon as fitsInViewport flips to
        //    false. Without this bypass, a book where exactly [appendOnlyMaxWindow] short
        //    front-matter chapters fit in one viewport would lock the reader: AppendOnly is
        //    exhausted, ShiftForward is suppressed by fitsInViewport, no scroll events fire
        //    at maxScrollY==0, and maybeShift never re-runs.
        // 2. atBottomOfLoadedWindow: the viewport bottom already touches the end of loaded
        //    content but the window is under its max size. ShiftForward here would drop the top
        //    chapter, reset scrollY to 0, trigger ShiftBackward (which prepends the dropped
        //    chapter at placeholder height), then overshoot the forward trigger again — an
        //    infinite oscillation. AppendOnly breaks the loop by extending forward without losing
        //    the opening chapter. The cap ([appendOnlyMaxWindow]) applies here because ShiftForward
        //    is the correct action once the window is large enough.
        val shouldAppendOnly = moreChaptersExist &&
            appendOnlyMaxWindow > 0 &&
            (fitsInViewport || (atBottomOfLoadedWindow && window.size < appendOnlyMaxWindow))

        // AppendOnly takes priority over ShiftForward: when the window can still grow, extend it
        // before evicting the top chapter. ShiftForward only fires once the window is full or
        // neither fit-in-viewport nor at-bottom conditions apply.
        return when {
            shouldShiftBackward -> Decision.ShiftBackward
            shouldAppendOnly -> Decision.AppendOnly
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
