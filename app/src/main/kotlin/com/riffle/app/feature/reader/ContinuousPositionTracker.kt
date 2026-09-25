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
     * The scrollY that lands [progression] within a chapter at [slotTop]/[slotHeight] — the inverse
     * of [locatorAt]. Because [locatorAt] measures progression at the viewport MIDPOINT, a restored
     * mid-chapter progression must be placed back at the midpoint (subtract half the viewport),
     * NOT at the top. Placing it at the top shifts the view down by half a viewport, so a saved
     * reading position would drift forward by half a screen on every reopen. A chapter start
     * (progression ~0, e.g. a TOC jump) stays top-aligned so it doesn't scroll up into the previous
     * chapter. Never negative.
     */
    fun scrollYForProgression(slotTop: Int, slotHeight: Int, progression: Float, viewportHeight: Int): Int {
        val base = slotTop + (progression * slotHeight).toInt()
        val y = if (progression <= 0.001f) base else base - viewportHeight / 2
        return y.coerceAtLeast(0)
    }

    /**
     * Resolve the scrollY that places an anchor (annotation decoration or DOM element with an id)
     * at the right viewport position for the given landing policy.
     *
     * - [alignToTop] = `true` (TOC tap / page-bookmark / chapter-start landing): anchor at the
     *   viewport top.
     * - [alignToTop] = `false` (highlight / note tap / search-result open): anchor at the viewport
     *   midpoint, mirroring [scrollYForProgression]. Subtracts half a viewport from the anchor's
     *   absolute Y so reading context is preserved above it. Near-chapter-start anchors clamp at
     *   `scrollY = 0`; the viewport can't scroll above content, and the anchor still lands above
     *   the midpoint but as close as the available space allows.
     *
     * Used by `ContinuousReaderView.scrollToLoadedChapter` and `openWindowAt` to share a single
     * landing rule — the previous duplicate inlined logic in the two paths was the seam where the
     * "highlights glued to top" bug slipped in twice.
     */
    fun anchorLandingScrollY(slotTop: Int, anchorOffsetWithinSlot: Int, viewportHeight: Int, alignToTop: Boolean): Int {
        val absoluteY = slotTop + anchorOffsetWithinSlot
        val target = if (alignToTop) absoluteY else absoluteY - viewportHeight / 2
        return target.coerceAtLeast(0)
    }

    /**
     * Whether an anchor whose absolute (parent-viewport) Y is [absoluteY] is already visible in a
     * viewport currently scrolled to [currentScrollY] with height [viewportHeight]. Null means
     * "couldn't resolve" — treat as not visible so the caller still navigates.
     *
     * Used by the continuous-mode same-document cross-reference handler to make an in-view link
     * tap a no-op: paginated mode gets this for free from `ColumnSnap.scrollToColumnJs` returning
     * `'same'` when the column is unchanged (see `snapToElement`), but continuous mode owns its
     * own scroll and must decide explicitly. Without the check, tapping an internal link whose
     * target is already on-screen would still recentre it AND drop a return-to-position card —
     * both wrong (the user is already looking at the target).
     */
    fun anchorAlreadyInViewport(absoluteY: Int?, currentScrollY: Int, viewportHeight: Int): Boolean =
        absoluteY != null && absoluteY >= currentScrollY && absoluteY < currentScrollY + viewportHeight

    /** Host that [ChapterWebView] serves all EPUB resources from. */
    const val RESOURCE_HOST = "readium_package"

    /**
     * Resolve a URL the WebView is about to navigate to (a tapped in-book link) to the EPUB resource
     * href it points at (keeping any `#fragment`), or null when it points outside the book (an
     * external http(s) URL). In-book resources are served at `https://[RESOURCE_HOST]/<href>`.
     */
    fun internalLinkHref(url: String): String? {
        val marker = "://$RESOURCE_HOST/"
        val i = url.indexOf(marker)
        return if (i >= 0) url.substring(i + marker.length) else null
    }

    /**
     * Index into [hrefs] (the reading order) of the chapter [targetHref] refers to, comparing on the
     * resource path so a `#fragment` on either side doesn't prevent a match. -1 when not found.
     */
    fun chapterIndexForHref(hrefs: List<String>, targetHref: String): Int {
        val target = targetHref.substringBefore('#')
        return hrefs.indexOfFirst { it.substringBefore('#') == target }
    }

    /**
     * Whether [targetHref]'s chapter is currently loaded in the sliding window that spans
     * `[topIndex, topIndex + loadedChapterCount)`. Used by the reader screen to suppress the
     * cross-resource nav-cover for a navigation that stays in-window — the cover otherwise hides
     * the already-smooth `NestedScrollView.smoothScrollTo` animation and the user only sees a
     * fade-to-snap.
     */
    fun isTargetInWindow(
        hrefs: List<String>,
        targetHref: String,
        topIndex: Int,
        loadedChapterCount: Int,
    ): Boolean {
        val idx = chapterIndexForHref(hrefs, targetHref)
        return idx in topIndex until (topIndex + loadedChapterCount)
    }

    /**
     * Pre-landing scrollY for a cross-window navigation that wants a smooth-tail reveal: land half
     * a viewport short of [targetY] under the still-showing nav cover, then the caller reveals
     * the cover and animates the remaining half-viewport with `smoothScrollTo(targetY)`. That
     * closes the "back button feels abrupt" gap in continuous mode without regressing the
     * hard-landing paths (initial open, resume, annotation focus) that still call `scrollTo`.
     */
    fun preLandY(targetY: Int, viewportHeight: Int): Int =
        (targetY - viewportHeight / 2).coerceAtLeast(0)

    /**
     * Pixels to scroll for a volume-key "page" in continuous mode: one viewport minus overlap so the
     * line at the seam isn't skipped. Matches the volume-key delta in the paginated/vertical path
     * via [ScrollBoundaryNavigationContainer.handleVolumeScroll] — keeping both modes on the same
     * step size is what makes rapid presses feel identical instead of "faster" in one mode.
     * Returns 0 for a non-positive viewport.
     */
    fun pageScrollDelta(viewportHeightPx: Int): Int =
        if (viewportHeightPx <= 0) 0 else (viewportHeightPx * ScrollBoundaryNavigationContainer.VOLUME_SCROLL_FRACTION).toInt()

    /** Bounds for [pageScrollDurationMs]; the max also sizes the [PageScrollCoalescer] validity
     *  window in [ContinuousWindowController] — a window slightly longer than a finished animation
     *  is harmless because the pending target then equals the settled scroll position. */
    internal const val PAGE_SCROLL_MIN_DURATION_MS = 200
    internal const val PAGE_SCROLL_MAX_DURATION_MS = 1000

    /**
     * Animation speed for a volume-key page scroll: ms per √(CSS px). Chromium's own rate for
     * `window.scrollBy({behavior:'smooth'})` is ~16.7 (= 1000/60). Continuous mode targets a
     * higher value — tune this constant to align with vertical mode's feel on device.
     */
    internal const val PAGE_SCROLL_MS_PER_SQRT_CSS_PX = 35.0

    /**
     * Animation duration for a volume-key page scroll of [distancePx] physical pixels, on a screen
     * with the given [density]. Uses [PAGE_SCROLL_MS_PER_SQRT_CSS_PX] scaled by √(CSS px) so
     * coalesced presses (larger distance) get proportionally longer glides. Chromium computes in
     * CSS pixels, so the physical distance is divided by [density] first; the result is clamped to
     * [[PAGE_SCROLL_MIN_DURATION_MS], [PAGE_SCROLL_MAX_DURATION_MS]]. Returns 0 for a non-positive
     * distance or density.
     */
    fun pageScrollDurationMs(distancePx: Int, density: Float): Int {
        if (distancePx <= 0 || density <= 0f) return 0
        val cssPx = distancePx / density
        val ms = PAGE_SCROLL_MS_PER_SQRT_CSS_PX * kotlin.math.sqrt(cssPx.toDouble())
        return ms.toInt().coerceIn(PAGE_SCROLL_MIN_DURATION_MS, PAGE_SCROLL_MAX_DURATION_MS)
    }

    /** A resolved volume-key page-scroll animation: scroll by [scrollBy] px over [durationMs]. */
    data class PageScrollAnimation(val scrollBy: Int, val durationMs: Int)

    /**
     * Animation for a volume-key page scroll from [currentScrollY] to the (coalescer-resolved,
     * clamped) [targetScrollY]. The duration follows the *actual* animated distance via
     * [pageScrollDurationMs], not the nominal per-press delta: at a content boundary the clamped
     * remainder shouldn't crawl over a full-press duration, and a coalesced press that extended the
     * in-flight target gets the longer glide Chromium's √distance heuristic would give it. Returns
     * null when there is nothing to animate (target equals current, or non-positive [density]).
     */
    fun pageScrollAnimation(currentScrollY: Int, targetScrollY: Int, density: Float): PageScrollAnimation? {
        val scrollBy = targetScrollY - currentScrollY
        if (scrollBy == 0 || density <= 0f) return null
        return PageScrollAnimation(
            scrollBy = scrollBy,
            durationMs = pageScrollDurationMs(kotlin.math.abs(scrollBy), density),
        )
    }

    /**
     * Resolve a text selection to the narrated-sentence id whose sentence contains it, for
     * "Play from here" in Continuous mode. [quoteTexts] maps sentence id → sentence text (built from
     * the readaloud quote map). Returns the id of the sentence that contains the selection (prefer a
     * full-text containment; fall back to containing the selection's leading chunk so a partial
     * selection still resolves), or null if nothing matches / the selection is blank.
     */
    fun sentenceIdForSelection(selectedText: String, quoteTexts: Map<String, String>): String? {
        val needle = selectedText.trim()
        if (needle.isEmpty()) return null
        quoteTexts.entries.firstOrNull { it.value.contains(needle) }?.let { return it.key }
        val head = needle.take(20)
        return quoteTexts.entries.firstOrNull { it.value.contains(head) }?.key
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
        atBottomOfLoadedWindow: Boolean = false,
    ): Boolean {
        val moreChaptersExist = topIndex + loadedChapterCount < readingOrderSize
        val pastBehindBudget = viewportChapterIndex - topIndex > chaptersBehind
        // Bottom-of-window trigger: when the trailing chapter(s) in the window are shorter than
        // half a viewport, the midpoint can never enter their slot no matter how far the user
        // scrolls — `pastBehindBudget` stays false forever and the reader walls off. This shows up
        // in elided (Highlights-mode) continuous reading where single-annotation chapters
        // synthesise to ~500 px pages. Fire when the scroll is clamped at the end of the loaded
        // content and chapters remain to append. Complements the midpoint trigger — does not
        // replace it — so the divider-page blank-flash guard the midpoint trigger was introduced
        // for is preserved.
        return moreChaptersExist && (pastBehindBudget || atBottomOfLoadedWindow)
    }

    data class InitialWindow(val topIndex: Int, val totalChapters: Int, val targetWindowIndex: Int) {
        /**
         * Window indices whose real height must be measured before the initial scroll is allowed
         * to fire: the target chapter ONLY.
         *
         * Cold-open latency is dominated by Chromium parsing + laying out the window's chapters,
         * and with every window chapter loading at once the three (or five) WebViews share one
         * renderer — the target chapter routinely finishes LAST (measured 4.3 s to first paint on
         * a 200 KB chapter book, with the 2.5 s fallback firing before the target had even
         * parsed). Gating on the target alone and loading the neighbours only after the reveal
         * ([deferredLoadOrder]) brings first paint down to a single chapter's load time.
         *
         * Neighbours stay at placeholder height until they load. A chapter BELOW the target
         * collapsing later only moves content below the viewport anchor; the chapter ABOVE
         * (window index 0) is compensated by the layout-synchronous scrollBy in
         * ContinuousWindowController's height handler, and the backward scroll floor holds the
         * reader at the boundary until it measures, exactly like a backward prepend.
         */
        fun pendingMeasureIndices(): Set<Int> = setOf(targetWindowIndex)

        /**
         * Window indices whose chapter load is deferred until the target chapter has been
         * revealed, in the order they should be loaded: the chapters AHEAD of the target first
         * (nearest first — the likeliest scroll direction), then the chapters BEHIND it (nearest
         * first). Loaded one at a time so a neighbour never competes with the visible chapter's
         * rasterisation or with another neighbour's parse.
         */
        fun deferredLoadOrder(): List<Int> =
            (targetWindowIndex + 1 until totalChapters).toList() + (targetWindowIndex - 1 downTo 0).toList()
    }

    /**
     * Compute the initial sliding-window layout for opening at [targetIndex] in a book of size
     * [allChaptersSize].
     *
     * The forward-shift trigger ([forwardShiftNeeded]) requires the viewport midpoint to advance
     * MORE THAN [chaptersBehind] slots past the top of the loaded window. When the user opens
     * the book near its start, the behind buffer is truncated (`min(chaptersBehind, targetIndex)`)
     * — but the *total* loaded count must still leave a slot at position > chaptersBehind for the
     * midpoint to land in, otherwise forward shifts never fire and the user walls off at the last
     * initially-loaded chapter.
     *
     * Fix: keep the total window size at [windowSize] regardless of how much behind buffer is
     * available, allocating the unused behind slots to the ahead buffer. So at chapter 0 we load
     * `windowSize` chapters ahead instead of just `chaptersAhead + 1`. Near the end of the book
     * the natural `allChaptersSize - topIndex` clamp still applies.
     *
     * Regression: PR #241 raised CHAPTERS_BEHIND from 1 to 3 to absorb consecutive short
     * chapters; that made the old `behind + 1 + chaptersAhead` formula return only 4 chapters
     * when opening at the start, and the `> 3` shift trigger required a 5th slot that never
     * existed. The reader got stuck at chapter 3.
     */
    fun initialWindow(
        targetIndex: Int,
        allChaptersSize: Int,
        chaptersBehind: Int,
        windowSize: Int,
    ): InitialWindow {
        require(windowSize > chaptersBehind) {
            "windowSize ($windowSize) must exceed chaptersBehind ($chaptersBehind); " +
                "otherwise the forward-shift trigger (gap > chaptersBehind) has no slot to land in " +
                "and the reader walls off at the last initially-loaded chapter."
        }
        val behind = minOf(chaptersBehind, targetIndex)
        val topIndex = targetIndex - behind
        val totalChapters = minOf(windowSize, allChaptersSize - topIndex)
        return InitialWindow(topIndex = topIndex, totalChapters = totalChapters, targetWindowIndex = behind)
    }

    /**
     * How many viewports tall a chapter WebView's *view* is allowed to be. The chapter's slot in
     * the scroll container still spans the full content height; the WebView itself is a window
     * of this size that slides through the slot (see [chapterWebViewWindowOffset]).
     *
     * Three viewports keeps a full screen of pre-rasterised content on each side of the visible
     * band as the window slides ([chapterWebViewWindowOffset]), so Chromium's raster keeps up
     * with a fling without visible checkerboarding, while the per-chapter tile footprint
     * (~3 screens × 4 bytes/px, ≈30 MB on a 1080 px phone) keeps three stacked chapters inside
     * Chromium's tile budget on 1 GB Android 7.1 tablets.
     */
    const val WEBVIEW_WINDOW_VIEWPORTS = 3

    /**
     * Fallback for the GPU's maximum renderable height when the hosting view has not yet drawn
     * with a hardware canvas ([android.graphics.Canvas.getMaximumBitmapHeight] is the authority
     * once available). 4096 is the smallest `GL_MAX_TEXTURE_SIZE` on any GPU Riffle ships to.
     */
    const val DEFAULT_MAX_RENDERABLE_HEIGHT_PX = 4096


    /**
     * Layout height for a chapter WebView whose content measures [contentHeightPx].
     *
     * A WebView laid out at its full content height cannot be drawn past the GPU's maximum
     * texture/viewport dimension (16 384 px on most GPUs, 4 096 on old ones): the hardware
     * compositor renders only the first [maxRenderableHeightPx] rows and everything below is
     * solid white (field repro 2026-09-25: a 148 529 px chapter opened at 97 % showed a blank
     * screen, with the raster cutting off at exactly 16 384 px into every chapter WebView).
     * Chromium also allocates tile memory proportionally to the view, so multi-hundred-thousand-
     * px views trip "tile memory limits exceeded" long before that.
     *
     * The WebView is therefore capped at [WEBVIEW_WINDOW_VIEWPORTS] viewports, never above the
     * renderable maximum, and never below one viewport (so the visible band always fits).
     * Chapters shorter than the cap keep their exact content height.
     */
    fun chapterWebViewHeight(contentHeightPx: Int, viewportHeightPx: Int, maxRenderableHeightPx: Int): Int {
        val viewport = viewportHeightPx.coerceAtLeast(1)
        val cap = minOf(viewport * WEBVIEW_WINDOW_VIEWPORTS, maxRenderableHeightPx).coerceAtLeast(viewport)
        return contentHeightPx.coerceIn(0, cap)
    }

    /**
     * Where a chapter WebView's window should start inside its slot's content for the given outer
     * scroll position, or `null` if it is already at [currentOffsetPx].
     *
     * The WebView is translated down by the returned offset inside its full-height slot and its
     * internal scroll is set to the same value, so content point `c` lands on screen at
     * `slotTop + c − scrollY` regardless of the offset — only which rows are *rendered* changes.
     *
     * Policy:
     *  - Slot entirely below the viewport: park the window at the chapter's top (offset 0).
     *  - Slot entirely above the viewport: park it at the chapter's bottom.
     *  - Otherwise keep the window centred on the visible band, clamped to the content, so it
     *    SLIDES with the outer scroll frame by frame.
     *
     * The window must slide continuously rather than jump in steps: the translation is applied
     * by the View system in the current frame but Chromium applies the internal scroll one frame
     * later, so a step of Δ px shows the content displaced by Δ for one frame (field repro
     * 2026-09-25: a 600 px backward jump on every re-centre during a fling). With a per-frame
     * slide the displacement equals one frame's scroll delta on every frame — uniform, so it
     * reads as smooth motion — and it vanishes at the chapter edges, where the clamp parks the
     * window and a neighbouring chapter (which has no lag) is on screen next to it.
     *
     * Parking neighbours at the edge that faces the viewport means the next chapter's first
     * screen is already rasterised when the reader scrolls across the boundary.
     */
    fun chapterWebViewWindowOffset(
        slotTop: Int,
        contentHeightPx: Int,
        webViewHeightPx: Int,
        currentOffsetPx: Int,
        scrollY: Int,
        viewportHeightPx: Int,
    ): Int? {
        val maxOffset = (contentHeightPx - webViewHeightPx).coerceAtLeast(0)
        if (maxOffset == 0) return if (currentOffsetPx != 0) 0 else null
        val bandTop = scrollY - slotTop
        val bandBottom = bandTop + viewportHeightPx
        val desired = when {
            bandBottom <= 0 -> 0
            bandTop >= contentHeightPx -> maxOffset
            else -> {
                val visTop = bandTop.coerceAtLeast(0)
                val visBottom = bandBottom.coerceAtMost(contentHeightPx)
                val spare = (webViewHeightPx - (visBottom - visTop)).coerceAtLeast(0)
                (visTop - spare / 2).coerceIn(0, maxOffset)
            }
        }
        return if (desired == currentOffsetPx) null else desired
    }

    /** What to do when Chromium reports a chapter WebView's internal scroll offset. */
    enum class InternalScrollCorrection { NONE, ADOPT, FOLD_INTO_OUTER_SCROLL }

    /**
     * Decide how [ContinuousWindowController] reacts to Chromium moving a chapter WebView's own
     * scroll offset to [reportedPx] while the managed window offset is [wantedPx].
     *
     *  - [InternalScrollCorrection.NONE]: already in sync, or [wantedPx] is beyond what the
     *    renderer can currently scroll to ([maxScrollPx], e.g. mid-reflow) — fighting that clamp
     *    would loop every frame; the next height measurement re-syncs instead.
     *  - [InternalScrollCorrection.ADOPT]: the deviation is at most one CSS px ([density] device
     *    px, rounded up). Chromium positions in CSS px and reports the container offset back
     *    rounded, so the controller moves the translation with it rather than re-asserting its own
     *    value on every frame.
     *  - [InternalScrollCorrection.FOLD_INTO_OUTER_SCROLL]: an unmanaged scroll — Chromium's
     *    selection auto-scroll when a handle is dragged past the window's edge, a focus scroll, a
     *    stray `window.find`. The content is taller than the view now, so Chromium CAN scroll it;
     *    snapping the offset back would fight it every frame (jitter, selection stuck at the
     *    band's edge). Instead the controller adopts the new offset and scrolls the outer view by
     *    the same delta, so the gesture becomes an ordinary page scroll.
     */
    fun internalScrollCorrection(reportedPx: Int, wantedPx: Int, density: Float, maxScrollPx: Int): InternalScrollCorrection {
        if (reportedPx == wantedPx) return InternalScrollCorrection.NONE
        val tolerance = kotlin.math.ceil(density.toDouble()).toInt()
        if (kotlin.math.abs(reportedPx - wantedPx) <= tolerance) return InternalScrollCorrection.ADOPT
        if (wantedPx > maxScrollPx) return InternalScrollCorrection.NONE
        return InternalScrollCorrection.FOLD_INTO_OUTER_SCROLL
    }

    /**
     * Scroll floor while a backward prepend is still an unmeasured placeholder: the placeholder's
     * bottom edge (its height, since the prepend always occupies slot 0 at top=0). Scrolling into
     * the blank placeholder maps those pixels to unseen content once the real height lands —
     * perceived as an abrupt jump deep into the previous chapter (field repro 2026-08-05). The
     * reader holds at the boundary until the chapter measures; 0 (no floor) otherwise.
     */
    fun backwardPrependScrollFloor(topSlotStillPlaceholder: Boolean, topSlotHeightPx: Int): Int =
        if (topSlotStillPlaceholder) topSlotHeightPx else 0

    /**
     * Lowest scrollY a BACKWARD fling starting at [scrollYStart] may reach: one page (90% of
     * [viewportHeightPx]) above the chapter boundary directly above the fling's starting
     * chapter, with sub-half-viewport resources (part titles) folded into that boundary.
     *
     * A ballistic fling travels several viewports; unconstrained, one crossing a chapter
     * boundary teleports the reader deep into the previous chapter — through content that is
     * often not even rasterized yet (field repro 2026-08-05: one fling from the Part II page
     * landed 4 viewports into ch06 on solid white). Capping the landing at one page past the
     * boundary shows exactly the previous chapter's tail; the next gesture reads on normally.
     * Flings that never reach their chapter's top are unaffected (the floor lies above their
     * ballistic target only when a crossing would occur). Returns 0 (no constraint) when the
     * start position is in the window's first slot.
     */
    fun backwardFlingFloor(
        scrollYStart: Int,
        window: List<ChapterSlot>,
        viewportHeightPx: Int,
    ): Int {
        if (window.isEmpty() || viewportHeightPx <= 0) return 0
        // Anchor on the viewport MIDPOINT, matching locatorAt's perception model. The viewport
        // top routinely sits a few hundred px inside the previous chapter's slot while the user
        // is already reading the next one (e.g. a nav smooth-scroll still settling); anchoring
        // on the top would resolve to the window's first slot and silently return no floor.
        val midpoint = scrollYStart + viewportHeightPx / 2
        var containing = window.indexOfLast { it.top <= midpoint }
        if (containing < 0) containing = 0
        // Fold tiny divider resources (shorter than half a viewport) into the boundary: the
        // boundary the reader perceives is the bottom of the previous SUBSTANTIAL chapter.
        // The fold is CAPPED at half a viewport of cumulative height: back matter is often a
        // RUN of tiny resources (about-the-author, publisher page, promo pages — field repro
        // 2026-08-05, "Free Excerpt" edition), and an unbounded fold walks the entire run so a
        // single pull skips them all and lands pages deep in whatever precedes them (the index).
        var boundaryIndex = containing
        var foldedPx = 0
        while (boundaryIndex > 0) {
            val above = window[boundaryIndex - 1]
            if (above.height >= viewportHeightPx / 2) break
            if (foldedPx + above.height > viewportHeightPx / 2) break
            foldedPx += above.height
            boundaryIndex--
        }
        if (boundaryIndex == 0) return 0
        val boundaryTop = window[boundaryIndex].top
        val onePageAbove = boundaryTop - viewportHeightPx * 9 / 10
        return onePageAbove.coerceAtLeast(0)
    }
}
