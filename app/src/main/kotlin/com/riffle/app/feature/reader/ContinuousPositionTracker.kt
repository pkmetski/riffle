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
     * Pixels to scroll for a volume-key "page" in continuous mode: one viewport minus a small overlap
     * so the line at the seam isn't skipped. Returns 0 for a non-positive viewport.
     */
    fun pageScrollDelta(viewportHeightPx: Int): Int =
        if (viewportHeightPx <= 0) 0 else (viewportHeightPx * 0.9f).toInt()

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
    ): Boolean {
        val moreChaptersExist = topIndex + loadedChapterCount < readingOrderSize
        val pastBehindBudget = viewportChapterIndex - topIndex > chaptersBehind
        return moreChaptersExist && pastBehindBudget
    }

    data class InitialWindow(val topIndex: Int, val totalChapters: Int, val targetWindowIndex: Int)

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
}
