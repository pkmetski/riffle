package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousPositionTrackerTest {

    // Window: chapter A = height 1000, top 0; chapter B = height 500, top 1000
    private val window = listOf(
        ContinuousPositionTracker.ChapterSlot("A.xhtml", top = 0, height = 1000),
        ContinuousPositionTracker.ChapterSlot("B.xhtml", top = 1000, height = 500),
    )

    @Test
    fun `scrollY 0 with viewport 800 — midY 400 is in chapter A, progression 0_4`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 0, viewportHeight = 800, window = window
        )
        assertEquals("A.xhtml", href)
        assertEquals(0.4f, prog, 0.001f)
    }

    @Test
    fun `scrollY 1000 with viewport 800 — midY 1400 is in chapter B, progression 0_8`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 1000, viewportHeight = 800, window = window
        )
        assertEquals("B.xhtml", href)
        assertEquals(0.8f, prog, 0.001f)
    }

    @Test
    fun `scrollY past all chapters — clamps to last chapter`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 5000, viewportHeight = 800, window = window
        )
        assertEquals("B.xhtml", href)
        assertEquals(1.0f, prog, 0.001f)
    }

    @Test
    fun `scrollOffsetFor returns correct offset for chapter B at progression 0_5`() {
        val offset = ContinuousPositionTracker.scrollOffsetFor(
            href = "B.xhtml", progression = 0.5f, window = window
        )
        // top=1000 + 0.5*500 = 1250
        assertEquals(1250, offset)
    }

    // ── scrollYForProgression (resume round-trip — inverse of locatorAt) ───────

    @Test
    fun `scrollYForProgression round-trips locatorAt so a resumed position does not drift`() {
        val viewport = 800
        // Sweep scroll offsets whose viewport midpoint sits comfortably inside a chapter (not on a
        // boundary, not past the last chapter — those clamp and can't round-trip). Each must survive
        // save (locatorAt) + restore unchanged.
        for (scrollY in listOf(200, 400, 700, 900)) {
            val (href, prog) = ContinuousPositionTracker.locatorAt(scrollY, viewport, window)
            val slot = window.first { it.href == href }
            val restored = ContinuousPositionTracker.scrollYForProgression(
                slot.top, slot.height, prog, viewport,
            )
            // Within rounding (progression is a float, scrollY/2 is integer-divided).
            assertEquals("scrollY=$scrollY", scrollY.toFloat(), restored.toFloat(), 2f)
        }
    }

    @Test
    fun `top-aligning a midpoint-saved progression drifts forward by half a viewport — do not use for resume`() {
        // Pins the failure mode behind the resume-overshoot bug: the call site that opens at a saved
        // position must invoke scrollYForProgression (midpoint inverse), NOT the slot.top + p*height
        // top-alignment used for TOC/bookmark jumps. Using top-alignment on a locatorAt-derived
        // progression lands the view exactly viewport/2 past the original scrollY on every reopen.
        val viewport = 800
        for (scrollY in listOf(200, 400, 700, 900)) {
            val (href, prog) = ContinuousPositionTracker.locatorAt(scrollY, viewport, window)
            val slot = window.first { it.href == href }
            val topAligned = (slot.top + (prog * slot.height).toInt()).coerceAtLeast(0)
            assertEquals(
                "scrollY=$scrollY", (scrollY + viewport / 2).toFloat(), topAligned.toFloat(), 2f,
            )
        }
    }

    @Test
    fun `scrollYForProgression top-aligns a chapter start and never goes negative`() {
        // progression 0 → top of the chapter (no half-viewport shift up into the previous chapter).
        assertEquals(1000, ContinuousPositionTracker.scrollYForProgression(1000, 500, 0f, 800))
        // a tiny progression near a chapter start would compute negative — clamps to 0.
        assertEquals(0, ContinuousPositionTracker.scrollYForProgression(0, 500, 0.1f, 800))
    }

    @Test
    fun `scrollOffsetFor returns null when chapter not in window`() {
        val offset = ContinuousPositionTracker.scrollOffsetFor(
            href = "C.xhtml", progression = 0.0f, window = window
        )
        assertNull(offset)
    }

    // ── forwardShiftNeeded ────────────────────────────────────────────────────

    // Window of 5: 1 behind + current + 3 ahead (chaptersBehind = 1).

    @Test
    fun `forwardShiftNeeded — midpoint past the behind budget triggers FORWARD`() {
        // window [0..4], chaptersBehind=1; midpoint in ch2 is >1 slot past top → shift
        assertTrue(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 2, topIndex = 0, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — midpoint within the behind budget returns false`() {
        // window [0..4], midpoint in ch1 == topIndex+chaptersBehind → no shift yet
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 1, topIndex = 0, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — no more chapters beyond window returns false`() {
        // window covers [15..19], readingOrderSize=20 — nothing left to append
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 18, topIndex = 15, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — after FORWARD shift midpoint sits at behind budget so false`() {
        // After a shift topIndex advances by 1 but the midpoint chapter is unchanged, so the
        // midpoint is back at topIndex+chaptersBehind → condition clears (no oscillation).
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 2, topIndex = 1, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — fires while several chapters remain loaded ahead (lead time)`() {
        // window [0..4], midpoint in ch2 → shift fires even though ch3,ch4 are still loaded
        // ahead. This is the look-ahead lead time that prevents blank gaps at chapter seams.
        assertTrue(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 2, topIndex = 0, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    // ── forward→backward oscillation — the "Children of Dune / Introduction" bug ──────────
    //
    // A short chapter (height < viewport) as the new first slot after a forward shift causes the
    // post-shift scrollY to satisfy the backward-shift condition (sY < firstChapterHeight/2),
    // creating an infinite loop. ContinuousReaderView.maybeShift() guards against this with the
    // justShiftedForward flag that suppresses the backward check for exactly one cycle.
    // These tests pin the mathematical invariant that makes that guard necessary.

    @Test
    fun `short new-first chapter — post-forward-shift scrollY always satisfies backward-shift condition`() {
        // Scenario: user scrolls forward through a short "CHILDREN OF DUNE" divider page (ch_N,
        // height H1 = 1050 device-px on a 2048-px screen) into the following "Introduction" chapter.
        // The forward shift removes ch_N-1 (H0) and adjusts scrollY = old_scrollY - H0.
        // The minimum old_scrollY that triggers the forward shift is H0 + H1 - viewport/2,
        // so the post-shift scrollY_min = H1 - viewport/2.
        //
        // Backward-shift condition: scrollY < firstChapterHeight/2.
        // For H1 < viewport: H1 - viewport/2 < H1/2, so the condition is always satisfied —
        // i.e. a backward shift fires in the very next cycle without the justShiftedForward guard.
        val viewport = 2048
        val H0 = 30_000   // large chapter removed by the forward shift
        val H1 = 1_050    // short divider page — height < viewport

        // Post-shift scrollY: after removeTop subtracts H0 from the minimum trigger scrollY.
        val postShiftScrollY = (H0 + H1 - viewport / 2) - H0
        assertEquals("post-shift scrollY = H1 - viewport/2", H1 - viewport / 2, postShiftScrollY)

        // Backward-shift threshold for the new first chapter (H1):
        val backwardThreshold = H1 / 2
        assertTrue(
            "Without justShiftedForward, a backward shift fires: scrollY=$postShiftScrollY < threshold=$backwardThreshold",
            postShiftScrollY < backwardThreshold,
        )
    }

    @Test
    fun `tall new-first chapter — post-forward-shift scrollY does NOT satisfy backward-shift condition`() {
        // A tall chapter (height >> viewport) as the new first slot is the normal case: the
        // post-shift scrollY lands deep inside it, well above firstChapterHeight/2, so no
        // spurious backward shift fires and no guard is needed for this path.
        val viewport = 2048
        val H0 = 30_000   // large chapter removed by the forward shift
        val H1 = 50_000   // tall new first chapter (normal body chapter)

        val postShiftScrollY = (H0 + H1 - viewport / 2) - H0
        val backwardThreshold = H1 / 2
        assertFalse(
            "Tall chapter: scrollY=$postShiftScrollY must NOT be < threshold=$backwardThreshold",
            postShiftScrollY < backwardThreshold,
        )
    }

    @Test
    fun `oscillation threshold — any chapter shorter than viewport triggers the bug`() {
        // The backward shift fires when: postShiftScrollY = H1 - viewport/2 < H1/2
        // This simplifies to: H1 < viewport. Verify for a range of short-chapter heights.
        val viewport = 2048
        for (H1 in listOf(200, 400, 800, 1000, 1050, 1500, 1999)) {
            val postShiftScrollY = H1 - viewport / 2
            val backwardThreshold = H1 / 2
            assertTrue(
                "Chapter of $H1 px (< viewport $viewport) must trigger oscillation without guard: " +
                    "postShiftScrollY=$postShiftScrollY, threshold=$backwardThreshold",
                postShiftScrollY < backwardThreshold,
            )
        }
    }

    @Test
    fun `oscillation threshold — chapters at least as tall as viewport do not trigger the bug`() {
        val viewport = 2048
        for (H1 in listOf(2048, 3000, 5000, 10_000, 50_000)) {
            val postShiftScrollY = H1 - viewport / 2
            val backwardThreshold = H1 / 2
            assertFalse(
                "Chapter of $H1 px (>= viewport $viewport) must NOT trigger oscillation: " +
                    "postShiftScrollY=$postShiftScrollY, threshold=$backwardThreshold",
                postShiftScrollY < backwardThreshold,
            )
        }
    }

    // ── backward→forward oscillation — the "В Дамаск / В Ерусалим" bug ─────────
    //
    // Short chapters cause an immediate forward shift after every backward shift.
    // Mechanism:
    //  1. topIndex points to ch-X (short, height H0 < viewport/2).
    //  2. User is at sY=0 → backward-shift fires (0 < H0/2).
    //  3. prependChapter(ch-X-1) adds placeholder P=viewport and fires scrollBy(+P). topIndex→X-1.
    //  4. New sY=P. Viewport midpoint = P + viewport/2.
    //  5. forwardShiftNeeded computes gap = viewportMidIndex − newTopIndex (X-1).
    //     If gap > CHAPTERS_BEHIND the forward shift fires, undoing the backward shift → loop.
    //
    // Gap = (number of consecutive short chapters above the viewport midpoint) + 1:
    //   • ch-X short, ch-X+1 tall: midpoint in ch-X+1 → viewportMidIndex=X+1, gap=(X+1)−(X-1)=2
    //   • ch-X and ch-X+1 both short (<viewport/2 combined), ch-X+2 tall:
    //       midpoint overshoots both → viewportMidIndex=X+2, gap=(X+2)−(X-1)=3
    //
    // In this book ch-63 "В Дамаск" (400 px) + ch-64 (tall) → gap=2 → need CHAPTERS_BEHIND ≥ 2.
    // ch-62 "В Ерусалим" (200 px) + ch-63 "В Дамаск" (400 px) + ch-64 (tall) → gap=3 → need ≥ 3.

    @Test
    fun `backward→forward oscillation — one short chapter then tall — gap is 2 — chaptersBehind 2 prevents it`() {
        // ch-X short (e.g. "В Дамаск", 400 px), ch-X+1 tall. Viewport midpoint lands in ch-X+1.
        // After backward shift: topIndex=X-1, viewportMidIndex=X+1, gap=2.
        val viewport = 2048
        val placeholder = viewport
        val H0 = 400                        // ch-X short chapter
        val H1 = 50_000                     // ch-X+1 tall chapter

        val sYAfterPrepend = 0 + placeholder
        val chXPlusOneStart = placeholder + H0
        val viewportMidpoint = sYAfterPrepend + viewport / 2

        // Midpoint does NOT overshoot the tall ch-X+1 (stays inside it).
        assertFalse(
            "Midpoint=$viewportMidpoint must NOT overshoot tall ch-X+1 end=${chXPlusOneStart + H1}",
            viewportMidpoint > chXPlusOneStart + H1,
        )
        // Midpoint IS past the short ch-X (viewport midpoint can never be inside a chapter
        // shorter than viewport/2 when sY is at the chapter's top).
        assertTrue(
            "Midpoint=$viewportMidpoint must be past short ch-X end=$chXPlusOneStart",
            viewportMidpoint > chXPlusOneStart,
        )

        // gap = (X+1) − (X-1) = 2
        val gapAfterBackwardShift = 2
        assertTrue(
            "chaptersBehind=1: gap $gapAfterBackwardShift > 1 → forward shift fires → oscillation",
            gapAfterBackwardShift > 1,
        )
        assertFalse(
            "chaptersBehind=2: gap $gapAfterBackwardShift > 2 is FALSE → backward shift sticks",
            gapAfterBackwardShift > 2,
        )
    }

    @Test
    fun `backward→forward oscillation — two consecutive short chapters then tall — gap is 3 — chaptersBehind 3 prevents it`() {
        // ch-X and ch-X+1 both short (combined < viewport/2), ch-X+2 tall.
        // "В Ерусалим" (200 px) + "В Дамаск" (400 px) in the 1001-Nights book.
        // After backward shift: topIndex=X-1, viewportMidIndex=X+2 (midpoint overshoots both
        // short chapters), gap=3.
        val viewport = 2048
        val placeholder = viewport
        val H0 = 200                        // ch-X short chapter (e.g. "В Ерусалим")
        val H1 = 400                        // ch-X+1 also short (e.g. "В Дамаск")

        val sYAfterPrepend = 0 + placeholder
        val chXPlusOneEnd = placeholder + H0 + H1
        val viewportMidpoint = sYAfterPrepend + viewport / 2

        // Combined height of both short chapters fits before the viewport midpoint.
        assertTrue(
            "H0+H1=${H0 + H1} must be < viewport/2=${viewport / 2} for midpoint to overshoot both",
            H0 + H1 < viewport / 2,
        )
        assertTrue(
            "Midpoint=$viewportMidpoint must overshoot ch-X+1 end=$chXPlusOneEnd",
            viewportMidpoint > chXPlusOneEnd,
        )

        // gap = (X+2) − (X-1) = 3
        val gapAfterBackwardShift = 3
        assertTrue(
            "chaptersBehind=2: gap $gapAfterBackwardShift > 2 → forward shift still fires",
            gapAfterBackwardShift > 2,
        )
        assertFalse(
            "chaptersBehind=3: gap $gapAfterBackwardShift > 3 is FALSE → backward shift sticks",
            gapAfterBackwardShift > 3,
        )
    }

    // ── internalLinkHref ──────────────────────────────────────────────────────

    @Test
    fun `internalLinkHref extracts the resource path for an in-book url`() {
        assertEquals(
            "text/ch5.xhtml",
            ContinuousPositionTracker.internalLinkHref("https://readium_package/text/ch5.xhtml"),
        )
    }

    @Test
    fun `internalLinkHref keeps the fragment`() {
        assertEquals(
            "text/ch5.xhtml#note3",
            ContinuousPositionTracker.internalLinkHref("https://readium_package/text/ch5.xhtml#note3"),
        )
    }

    @Test
    fun `internalLinkHref returns null for an external url`() {
        assertNull(ContinuousPositionTracker.internalLinkHref("https://example.com/page"))
        assertNull(ContinuousPositionTracker.internalLinkHref("mailto:a@b.com"))
    }

    // ── chapterIndexForHref ───────────────────────────────────────────────────

    private val order = listOf("text/ch1.xhtml", "text/ch2.xhtml", "text/ch3.xhtml")

    @Test
    fun `chapterIndexForHref matches exact href`() {
        assertEquals(1, ContinuousPositionTracker.chapterIndexForHref(order, "text/ch2.xhtml"))
    }

    @Test
    fun `chapterIndexForHref matches ignoring a fragment on the target`() {
        assertEquals(2, ContinuousPositionTracker.chapterIndexForHref(order, "text/ch3.xhtml#figure-4-1"))
    }

    @Test
    fun `chapterIndexForHref returns -1 when not found`() {
        assertEquals(-1, ContinuousPositionTracker.chapterIndexForHref(order, "text/missing.xhtml"))
    }

    // ── pageScrollDelta ───────────────────────────────────────────────────────

    @Test
    fun `pageScrollDelta is a viewport minus a small overlap`() {
        // 0·8 of the viewport — matches the paginated/vertical volume-key path so rapid presses
        // travel the same distance in both reader modes. Regression pin: if this drifts back to
        // 0·9, the assertion flips red.
        assertEquals(800, ContinuousPositionTracker.pageScrollDelta(1000))
    }

    @Test
    fun `pageScrollDelta is zero for a non-positive viewport`() {
        assertEquals(0, ContinuousPositionTracker.pageScrollDelta(0))
        assertEquals(0, ContinuousPositionTracker.pageScrollDelta(-5))
    }

    // ── Bookmark vs. continuous-resume alignment ──────────────────────────────
    //
    // scrollYForProgression is the midpoint-inverse of locatorAt: it's correct for continuous
    // round-trips (save position → resume) but wrong for external locators (bookmark/CFI) whose
    // progression was measured at content top rather than viewport midpoint. For those,
    // scrollOffsetFor gives the top-aligned scrollY.

    @Test
    fun `scrollOffsetFor gives top-aligned scrollY for a bookmark locator — content at viewport top`() {
        // Bookmark at progression 0·5 through chapter A (height 1000, top 0): content is at offset 500.
        // Top-aligned: scrollY = 0 + 0.5 * 1000 = 500 → bookmarked line at viewport top.
        val offset = ContinuousPositionTracker.scrollOffsetFor("A.xhtml", 0.5f, window)
        assertEquals(500, offset)
    }

    @Test
    fun `scrollYForProgression for the same bookmark progression places content at viewport midpoint`() {
        // scrollYForProgression subtracts viewportHeight/2 (midpoint-inverse of locatorAt).
        // For a progression measured at content top (bookmark), this scrolls viewportHeight/2 too
        // high — the bug that alignToTop = true fixes.
        val viewport = 800
        val midpointAligned = ContinuousPositionTracker.scrollYForProgression(
            slotTop = 0, slotHeight = 1000, progression = 0.5f, viewportHeight = viewport,
        )
        val topAligned = ContinuousPositionTracker.scrollOffsetFor("A.xhtml", 0.5f, window)!!

        // midpoint-aligned = 500 - 400 = 100; top-aligned = 500. Difference = viewportHeight/2.
        assertEquals(topAligned - viewport / 2, midpointAligned)
        assertEquals(viewport / 2, topAligned - midpointAligned)
    }

    @Test
    fun `top-aligned bookmark at chapter start lands at chapter top, not before it`() {
        // progression 0 on chapter B (top=1000): both alignments agree — the chapter start is at 1000.
        val topAligned = ContinuousPositionTracker.scrollOffsetFor("B.xhtml", 0f, window)
        assertEquals(1000, topAligned)
    }

    @Test
    fun `top-aligned bookmark at chapter end lands at the last content, not past it`() {
        // progression 1.0 on chapter A (height 1000, top 0): scrollY = 0 + 1000 = 1000
        // (the very bottom of chapter A, which is also where chapter B begins).
        val topAligned = ContinuousPositionTracker.scrollOffsetFor("A.xhtml", 1.0f, window)
        assertEquals(1000, topAligned)
    }

    // ── sentenceIdForSelection (Play from here) ───────────────────────────────

    private val quoteTexts = mapOf(
        "c1-s1" to "It was a bright cold day in April.",
        "c1-s2" to "The clocks were striking thirteen.",
        "c1-s3" to "Winston Smith slipped quickly through the glass doors.",
    )

    @Test
    fun `sentenceIdForSelection finds the sentence containing the full selection`() {
        assertEquals("c1-s2", ContinuousPositionTracker.sentenceIdForSelection("clocks were striking", quoteTexts))
    }

    @Test
    fun `sentenceIdForSelection falls back to the leading chunk of a partial selection`() {
        // Selection runs past the sentence end; the leading chunk still pins it.
        assertEquals(
            "c1-s3",
            ContinuousPositionTracker.sentenceIdForSelection("Winston Smith slipped quickly and then more text", quoteTexts),
        )
    }

    @Test
    fun `sentenceIdForSelection returns null for blank or unmatched selection`() {
        assertNull(ContinuousPositionTracker.sentenceIdForSelection("   ", quoteTexts))
        assertNull(ContinuousPositionTracker.sentenceIdForSelection("nothing like this exists here", quoteTexts))
    }

    @Test
    fun `sentenceIdForSelection returns the first matching sentence even when the word also appears later`() {
        // Text matching always returns the FIRST sentence in the map whose text contains the
        // selected word, regardless of which occurrence the user actually selected. This is the
        // known limitation of the text-match path — why geometry-based resolution (via
        // resolveSelectionSentenceJs) is the preferred primary path in continuous mode.
        val duplicateWordQuotes = mapOf(
            "c1-s1" to "The cat sat on the mat.",
            "c1-s2" to "The cat ran away quickly.",
        )
        assertEquals(
            "text-match picks the first sentence containing the word, not the selected position",
            "c1-s1",
            ContinuousPositionTracker.sentenceIdForSelection("cat", duplicateWordQuotes),
        )
    }

    // ── initialWindow ────────────────────────────────────────────────────────
    //
    // Regression coverage for "stuck at chapter N" when opening near the start of the book.
    // With CHAPTERS_BEHIND=3 (raised from 1 in PR #241) the forward-shift trigger requires the
    // viewport midpoint to be at slot index > 3. The old formula `behind + 1 + CHAPTERS_AHEAD`
    // returned only 4 chapters when opening at the start, so slot 4 never existed and the
    // window never shifted — the user walled off at the last loaded chapter.

    @Test
    fun `initialWindow at chapter 0 loads the full window ahead (no behind buffer available)`() {
        // Opening at the very first chapter: no chapters behind, all spare slots go to ahead.
        val w = ContinuousPositionTracker.initialWindow(
            targetIndex = 0, allChaptersSize = 50, chaptersBehind = 3, windowSize = 7,
        )
        assertEquals(0, w.topIndex)
        assertEquals(7, w.totalChapters)
        assertEquals(0, w.targetWindowIndex)
        // Critical invariant: total > chaptersBehind, so the forward-shift trigger can fire.
        assertTrue("must load > chaptersBehind chapters or shift trigger is unreachable",
            w.totalChapters > 3)
    }

    @Test
    fun `initialWindow at chapter 1 reallocates 2 unused behind slots to ahead`() {
        val w = ContinuousPositionTracker.initialWindow(
            targetIndex = 1, allChaptersSize = 50, chaptersBehind = 3, windowSize = 7,
        )
        assertEquals(0, w.topIndex)
        assertEquals(7, w.totalChapters)
        assertEquals(1, w.targetWindowIndex)
    }

    @Test
    fun `initialWindow at chapter 2 reallocates 1 unused behind slot to ahead`() {
        val w = ContinuousPositionTracker.initialWindow(
            targetIndex = 2, allChaptersSize = 50, chaptersBehind = 3, windowSize = 7,
        )
        assertEquals(0, w.topIndex)
        assertEquals(7, w.totalChapters)
        assertEquals(2, w.targetWindowIndex)
    }

    @Test
    fun `initialWindow mid-book uses full behind plus full ahead (unchanged from prior behavior)`() {
        val w = ContinuousPositionTracker.initialWindow(
            targetIndex = 25, allChaptersSize = 50, chaptersBehind = 3, windowSize = 7,
        )
        assertEquals(22, w.topIndex)
        assertEquals(7, w.totalChapters)
        assertEquals(3, w.targetWindowIndex)
    }

    @Test
    fun `initialWindow near end of book clamps total to remaining chapters`() {
        // 50-chapter book, opening at chapter 48: topIndex=45, only 5 chapters remain.
        val w = ContinuousPositionTracker.initialWindow(
            targetIndex = 48, allChaptersSize = 50, chaptersBehind = 3, windowSize = 7,
        )
        assertEquals(45, w.topIndex)
        assertEquals(5, w.totalChapters)
        assertEquals(3, w.targetWindowIndex)
    }

    @Test
    fun `initialWindow in a tiny book clamps total to allChaptersSize`() {
        // 3-chapter book opened at chapter 1: behind=1, total clamped to 3.
        val w = ContinuousPositionTracker.initialWindow(
            targetIndex = 1, allChaptersSize = 3, chaptersBehind = 3, windowSize = 7,
        )
        assertEquals(0, w.topIndex)
        assertEquals(3, w.totalChapters)
        assertEquals(1, w.targetWindowIndex)
    }

    // ── anchorLandingScrollY ────────────────────────────────────────────────────────────────

    @Test
    fun `anchorLandingScrollY with alignToTop places anchor at viewport top`() {
        // Chapter at scroll y=5000, anchor 300 px into the chapter, 1080-px viewport. A TOC tap
        // or page-bookmark target should put the anchor flush at scrollY=5300 — the anchor at
        // viewport top.
        val y = ContinuousPositionTracker.anchorLandingScrollY(
            slotTop = 5000,
            anchorOffsetWithinSlot = 300,
            viewportHeight = 1080,
            alignToTop = true,
        )
        assertEquals(5300, y)
    }

    @Test
    fun `anchorLandingScrollY without alignToTop centres the anchor in the viewport`() {
        // Same chapter / anchor, but a highlight tap should pull the viewport so the anchor sits
        // at the midpoint: scrollY = anchorAbsoluteY - viewportHeight/2 = 5300 - 540 = 4760.
        val y = ContinuousPositionTracker.anchorLandingScrollY(
            slotTop = 5000,
            anchorOffsetWithinSlot = 300,
            viewportHeight = 1080,
            alignToTop = false,
        )
        assertEquals(4760, y)
    }

    @Test
    fun `anchorLandingScrollY clamps the midpoint target at scrollY=0 when content is too near the start`() {
        // Chapter near the book start (slot.top=100), anchor 50 px in. The midpoint target would
        // be 150 - 540 = -390; the viewport can't scroll above zero, so clamp. The anchor still
        // lands above the midpoint, just as close as the available space allows.
        val y = ContinuousPositionTracker.anchorLandingScrollY(
            slotTop = 100,
            anchorOffsetWithinSlot = 50,
            viewportHeight = 1080,
            alignToTop = false,
        )
        assertEquals(0, y)
    }

    @Test
    fun `anchorLandingScrollY with alignToTop clamps at scrollY=0 when the anchor is above the viewport`() {
        // Defensive: a near-zero anchor with alignToTop should still never produce a negative
        // scrollY. (Both branches share the coerceAtLeast(0) tail; this pins it explicitly.)
        val y = ContinuousPositionTracker.anchorLandingScrollY(
            slotTop = 0,
            anchorOffsetWithinSlot = 0,
            viewportHeight = 1080,
            alignToTop = true,
        )
        assertEquals(0, y)
    }

    // Cross-reference tap on an on-screen target must be a no-op in continuous mode: an anchor
    // whose absolute Y falls anywhere inside [scrollY, scrollY + viewportHeight) is already
    // visible, so the caller must skip both the scroll and the return-to-position capture.
    // Without this, tapping an internal link whose target is already on the page recentres the
    // page AND drops a "Back" card — the regression this pins.

    @Test
    fun `anchorAlreadyInViewport — anchor inside the visible band is true`() {
        assertTrue(
            ContinuousPositionTracker.anchorAlreadyInViewport(
                absoluteY = 1500, currentScrollY = 1000, viewportHeight = 800,
            ),
        )
    }

    @Test
    fun `anchorAlreadyInViewport — anchor exactly at the top edge is true`() {
        assertTrue(
            ContinuousPositionTracker.anchorAlreadyInViewport(
                absoluteY = 1000, currentScrollY = 1000, viewportHeight = 800,
            ),
        )
    }

    @Test
    fun `anchorAlreadyInViewport — anchor above the viewport is false`() {
        assertFalse(
            ContinuousPositionTracker.anchorAlreadyInViewport(
                absoluteY = 500, currentScrollY = 1000, viewportHeight = 800,
            ),
        )
    }

    @Test
    fun `anchorAlreadyInViewport — anchor at or past the bottom edge is false`() {
        // scrollY + viewportHeight is the first Y NOT visible (half-open interval).
        assertFalse(
            ContinuousPositionTracker.anchorAlreadyInViewport(
                absoluteY = 1800, currentScrollY = 1000, viewportHeight = 800,
            ),
        )
        assertFalse(
            ContinuousPositionTracker.anchorAlreadyInViewport(
                absoluteY = 2500, currentScrollY = 1000, viewportHeight = 800,
            ),
        )
    }

    @Test
    fun `anchorAlreadyInViewport — null absoluteY is false so caller still navigates`() {
        assertFalse(
            ContinuousPositionTracker.anchorAlreadyInViewport(
                absoluteY = null, currentScrollY = 1000, viewportHeight = 800,
            ),
        )
    }

    // Reading order: 6 chapters; sliding window loaded is [topIndex=2, topIndex+4) = ch2..ch5.
    private val readingOrder = listOf("ch0.xhtml", "ch1.xhtml", "ch2.xhtml", "ch3.xhtml", "ch4.xhtml", "ch5.xhtml")

    @Test
    fun `isTargetInWindow — chapter inside the window returns true`() {
        assertTrue(
            ContinuousPositionTracker.isTargetInWindow(
                hrefs = readingOrder, targetHref = "ch3.xhtml", topIndex = 2, loadedChapterCount = 4,
            ),
        )
    }

    @Test
    fun `isTargetInWindow — chapter before the window is false so cover shows during rebuild`() {
        assertFalse(
            ContinuousPositionTracker.isTargetInWindow(
                hrefs = readingOrder, targetHref = "ch1.xhtml", topIndex = 2, loadedChapterCount = 4,
            ),
        )
    }

    @Test
    fun `isTargetInWindow — chapter one past the window is false`() {
        // topIndex=2, loadedChapterCount=4 → last in-window index = 5; but if only 3 loaded, ch5 is out.
        assertFalse(
            ContinuousPositionTracker.isTargetInWindow(
                hrefs = readingOrder, targetHref = "ch5.xhtml", topIndex = 2, loadedChapterCount = 3,
            ),
        )
    }

    @Test
    fun `isTargetInWindow — fragment on either side does not prevent match`() {
        assertTrue(
            ContinuousPositionTracker.isTargetInWindow(
                hrefs = readingOrder, targetHref = "ch3.xhtml#fig-4-1", topIndex = 2, loadedChapterCount = 4,
            ),
        )
    }

    @Test
    fun `isTargetInWindow — unknown href is false`() {
        assertFalse(
            ContinuousPositionTracker.isTargetInWindow(
                hrefs = readingOrder, targetHref = "unknown.xhtml", topIndex = 0, loadedChapterCount = 6,
            ),
        )
    }

    @Test
    fun `preLandY — subtracts half a viewport so smoothScrollTo has room to animate on reveal`() {
        // targetY = 4000, viewport = 1200 → preLand = 4000 - 600 = 3400
        assertEquals(3400, ContinuousPositionTracker.preLandY(targetY = 4000, viewportHeight = 1200))
    }

    @Test
    fun `preLandY — clamps at zero when target is within half a viewport of the top`() {
        assertEquals(0, ContinuousPositionTracker.preLandY(targetY = 200, viewportHeight = 1200))
    }
}
