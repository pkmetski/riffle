package com.riffle.feature.reader

import com.riffle.core.domain.ReaderOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that gives iOS a Continuous mode.
 *
 * Runs on `iosSimulatorArm64Test`, which is the platform that needs it: Android's Continuous mode
 * never consults this because `ContinuousReaderView` has no resource boundary to be at.
 */
class ContinuousBoundaryAdvanceTest {

    private val atBottom = NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false)
    private val atTop = NavigatorScrollBoundary(atForwardBoundary = false, atBackwardBoundary = true)
    private val mid = NavigatorScrollBoundary.None
    private val bothEnds = NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = true)

    private fun ContinuousBoundaryAdvancePolicy.decideAt(
        boundary: NavigatorScrollBoundary,
        nowMs: Long,
        orientation: ReaderOrientation = ReaderOrientation.Continuous,
        canGoForward: Boolean = true,
        canGoBackward: Boolean = true,
    ) = decide(orientation, boundary, canGoForward, canGoBackward, nowMs)

    @Test
    fun continuousAdvancesForwardWhenTheReaderScrollsIntoTheBottomOfAResource() {
        val policy = ContinuousBoundaryAdvancePolicy()
        assertEquals(BoundaryAdvance.None, policy.decideAt(mid, 0))
        assertEquals(
            BoundaryAdvance.Forward,
            policy.decideAt(atBottom, 100),
            "arriving at the bottom of a resource in Continuous must cross into the next one",
        )
    }

    @Test
    fun continuousAdvancesBackwardWhenTheReaderScrollsIntoTheTopOfAResource() {
        val policy = ContinuousBoundaryAdvancePolicy()
        assertEquals(BoundaryAdvance.None, policy.decideAt(mid, 0))
        assertEquals(BoundaryAdvance.Backward, policy.decideAt(atTop, 100))
    }

    @Test
    fun verticalNeverAdvancesByItself() {
        // The whole point of having two scrolling modes: Vertical's chapter end is a wall the
        // reader pushes through deliberately. If this returned Forward, Vertical and Continuous
        // would be the same mode again — from the other direction this time.
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0, orientation = ReaderOrientation.Vertical)
        assertEquals(
            BoundaryAdvance.None,
            policy.decideAt(atBottom, 100, orientation = ReaderOrientation.Vertical),
        )
    }

    @Test
    fun paginatedNeverAdvances() {
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0, orientation = ReaderOrientation.Horizontal)
        assertEquals(
            BoundaryAdvance.None,
            policy.decideAt(atBottom, 100, orientation = ReaderOrientation.Horizontal),
        )
    }

    @Test
    fun restingAtTheBottomDoesNotKeepAdvancing() {
        // Edge-triggered, not level-triggered. A reader who has arrived at the end of a chapter
        // and is reading its last paragraph sits at the boundary for as long as they like.
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0)
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(atBottom, 100))
        assertEquals(BoundaryAdvance.None, policy.decideAt(atBottom, 5_000))
        assertEquals(BoundaryAdvance.None, policy.decideAt(atBottom, 50_000))
    }

    @Test
    fun theLandingAfterAnAdvanceIsNotItselfACrossing() {
        // A forward crossing lands at the TOP of the next resource, which is a rising backward
        // edge. Without the cooldown the reader would bounce straight back.
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0)
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(atBottom, 100))
        assertEquals(BoundaryAdvance.None, policy.decideAt(atTop, 220))
        // …and once the cooldown lapses the reader is still at the top, so there is no new edge.
        assertEquals(BoundaryAdvance.None, policy.decideAt(atTop, 2_000))
    }

    @Test
    fun aResourceShorterThanTheViewportAutoAdvancesForwardAfterDwell() {
        // A copyright page shorter than the viewport reports both boundaries simultaneously. The
        // policy must not cascade (advance immediately, walking the whole book in one frame), but
        // must also not block forever — after the dwell period the reader crosses forward.
        val policy = ContinuousBoundaryAdvancePolicy(shortChapterDwellMs = 100L)
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 0))
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 99))
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(bothEnds, 100))
    }

    @Test
    fun consecutiveShortChaptersEachRequireTheirOwnDwellBeforeAdvancing() {
        // Three short chapters back-to-back must each cost a full dwell — no cascade to the next
        // one the moment the current one fires.
        val policy = ContinuousBoundaryAdvancePolicy(shortChapterDwellMs = 100L)
        // First short chapter advances at t=100
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 0))
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(bothEnds, 100))
        // After the advance, another bothEnds probe (still loading next chapter) starts a new dwell
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 101))
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 200))
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(bothEnds, 201))
    }

    @Test
    fun aShortChapterAtTheEndOfTheBookAdvancesBackwardAfterDwell() {
        // Last chapter is short: canGoForward=false, so after the dwell it goes backward instead.
        val policy = ContinuousBoundaryAdvancePolicy(shortChapterDwellMs = 100L)
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 0, canGoForward = false))
        assertEquals(BoundaryAdvance.Backward, policy.decideAt(bothEnds, 100, canGoForward = false))
    }

    @Test
    fun aShortChapterAtBothEdgesOfTheBookDoesNotAdvance() {
        // Single-chapter book that is shorter than the viewport — nothing to advance to.
        val policy = ContinuousBoundaryAdvancePolicy(shortChapterDwellMs = 100L)
        assertEquals(
            BoundaryAdvance.None,
            policy.decideAt(bothEnds, 100, canGoForward = false, canGoBackward = false),
        )
    }

    @Test
    fun aTocJumpToAShortChapterRestartsTheDwell() {
        // Navigating via TOC to a short chapter (suppressUntilTheReaderLeavesTheBoundary) must
        // reset the dwell timer — otherwise a dwell started before the TOC tap would fire
        // immediately on the new chapter and advance past it.
        val policy = ContinuousBoundaryAdvancePolicy(shortChapterDwellMs = 100L)
        // Start dwell on a short chapter at t=0 — without the reset, advance would fire at t=100
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 0))
        // TOC tap at conceptual t=90 resets the dwell timer
        policy.suppressUntilTheReaderLeavesTheBoundary()
        // First probe after the reset at t=95 starts a fresh dwell from 95
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 95))
        // Advance fires 100 ms after the NEW dwell started (at t=95), so at t=195
        assertEquals(BoundaryAdvance.None, policy.decideAt(bothEnds, 194))
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(bothEnds, 195))
    }

    @Test
    fun theEndOfTheBookDoesNotAdvance() {
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0)
        assertEquals(BoundaryAdvance.None, policy.decideAt(atBottom, 100, canGoForward = false))
    }

    @Test
    fun theStartOfTheBookDoesNotAdvance() {
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0)
        assertEquals(BoundaryAdvance.None, policy.decideAt(atTop, 100, canGoBackward = false))
    }

    @Test
    fun aTocJumpDoesNotBounceTheReaderIntoThePreviousChapter() {
        // The reader taps a TOC entry and lands at the top of the chosen chapter. That landing is
        // a backward boundary through no scrolling of their own. Suppressing it is what stops the
        // policy from immediately crossing into the chapter *before* the one they asked for.
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0)
        policy.suppressUntilTheReaderLeavesTheBoundary()
        assertEquals(BoundaryAdvance.None, policy.decideAt(atTop, 100))
        assertEquals(BoundaryAdvance.None, policy.decideAt(atTop, 5_000))
        // …and a genuine scroll up after reading down into the chapter still crosses.
        assertEquals(BoundaryAdvance.None, policy.decideAt(mid, 6_000))
        assertEquals(BoundaryAdvance.Backward, policy.decideAt(atTop, 6_100))
    }

    @Test
    fun leavingContinuousForgetsTheEdges() {
        // Flip to Vertical while resting at the bottom, then back to Continuous: the boundary
        // that was already true must not count as a crossing the moment the mode returns, and
        // must still count once the reader genuinely leaves it and comes back.
        val policy = ContinuousBoundaryAdvancePolicy()
        policy.decideAt(mid, 0)
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(atBottom, 100))
        assertEquals(
            BoundaryAdvance.None,
            policy.decideAt(atBottom, 2_000, orientation = ReaderOrientation.Vertical),
        )
        assertEquals(BoundaryAdvance.None, policy.decideAt(mid, 3_000))
        assertEquals(BoundaryAdvance.Forward, policy.decideAt(atBottom, 3_100))
    }

    // ── Auto-scroll at a resource end ────────────────────────────────────────────

    @Test
    fun autoScrollCrossesChaptersInContinuousAndStopsInVertical() {
        assertEquals(
            AutoScrollStall.AdvanceResource,
            autoScrollStallAction(ReaderOrientation.Continuous, canGoForward = true),
            "hands-free reading in Continuous must not stop at every chapter end",
        )
        assertEquals(
            AutoScrollStall.EndOfBook,
            autoScrollStallAction(ReaderOrientation.Vertical, canGoForward = true),
        )
        assertEquals(
            AutoScrollStall.EndOfBook,
            autoScrollStallAction(ReaderOrientation.Continuous, canGoForward = false),
        )
    }

    // ── Spine index ──────────────────────────────────────────────────────────────

    @Test
    fun spineIndexMatchesAcrossHrefNormalisation() {
        val spine = listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/ch3.xhtml")
        assertEquals(1, spineIndexOfHref(spine, "/OEBPS/ch2.xhtml"))
        assertEquals(2, spineIndexOfHref(spine, "OEBPS/ch3.xhtml"))
        assertEquals(-1, spineIndexOfHref(spine, "OEBPS/nope.xhtml"))
    }

    // ── The probe parsers ────────────────────────────────────────────────────────

    @Test
    fun boundaryProbeParsesBothHostsMarshalling() {
        // WKWebView hands back the bare string; Android's evaluateJavascript JSON-encodes it.
        assertEquals(
            NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false),
            ScrollProbes.parseScrollBoundary("true,false"),
        )
        assertEquals(
            NavigatorScrollBoundary(atForwardBoundary = false, atBackwardBoundary = true),
            ScrollProbes.parseScrollBoundary("\"false,true\""),
        )
        assertEquals(
            NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = true),
            ScrollProbes.parseScrollBoundary(" true , true "),
        )
    }

    @Test
    fun anUnparseableBoundaryProbeIsNoBoundary() {
        // "No boundary" is the only safe default: it is the answer that never navigates.
        assertEquals(NavigatorScrollBoundary.None, ScrollProbes.parseScrollBoundary(null))
        assertEquals(NavigatorScrollBoundary.None, ScrollProbes.parseScrollBoundary(""))
        assertEquals(NavigatorScrollBoundary.None, ScrollProbes.parseScrollBoundary("true"))
        assertEquals(NavigatorScrollBoundary.None, ScrollProbes.parseScrollBoundary("null"))
        assertEquals(NavigatorScrollBoundary.None, ScrollProbes.parseScrollBoundary("1,0"))
    }

    @Test
    fun viewportFractionRejectsEverythingBookmarkEpsCannotUse() {
        assertEquals(0.25, ScrollProbes.parseViewportFraction("0.25"))
        assertEquals(0.25, ScrollProbes.parseViewportFraction("\"0.25\""))
        assertNull(ScrollProbes.parseViewportFraction(null))
        assertNull(ScrollProbes.parseViewportFraction(""))
        assertNull(ScrollProbes.parseViewportFraction("\"\""))
        assertNull(ScrollProbes.parseViewportFraction("null"))
        assertNull(ScrollProbes.parseViewportFraction("0"))
        assertNull(ScrollProbes.parseViewportFraction("-0.5"))
        assertNull(ScrollProbes.parseViewportFraction("not a number"))
    }

    @Test
    fun singleBooleanProbeParsesBothHostsMarshalling() {
        assertTrue(ScrollProbes.parseBooleanProbe("true"))
        assertTrue(ScrollProbes.parseBooleanProbe("\"true\""))
        assertTrue(!ScrollProbes.parseBooleanProbe("false"))
        assertTrue(!ScrollProbes.parseBooleanProbe(null))
    }

    @Test
    fun theBoundaryProbeAsksTheSameQuestionAsTheTwoSingleProbes() {
        // Android polls the two single scripts; iOS polls the combined one. If the predicates
        // ever drift, the two platforms disagree about where a chapter ends and only one of them
        // crosses it. Pinning the shared substring is what keeps the edit honest.
        assertTrue(
            ScrollProbes.BOUNDARY_PROBE_JS.contains(
                "window.scrollY + window.innerHeight >= document.body.scrollHeight - 4",
            ),
            "the combined probe must use the same forward predicate as AT_FORWARD_BOUNDARY_JS",
        )
        assertTrue(
            ScrollProbes.BOUNDARY_PROBE_JS.contains("window.scrollY <= 4"),
            "the combined probe must use the same backward predicate as AT_BACKWARD_BOUNDARY_JS",
        )
    }

    @Test
    fun theViewportFractionScriptPicksTheOverflowAxis() {
        // Paginated overflows horizontally, the scrolling modes vertically. One script serves all
        // three reading modes only because it chooses; a height-only measure would answer 1.0 for
        // every paginated chapter and widen the bookmark window to the whole book.
        assertTrue(ScrollProbes.VIEWPORT_FRACTION_JS.contains("sw > iw ? (iw / sw)"))
        assertTrue(ScrollProbes.VIEWPORT_FRACTION_JS.contains("ih / sh"))
    }
}
