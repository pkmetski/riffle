package com.riffle.feature.reader

import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.normalizeEpubHref

/** What a scroll boundary means for the reader right now. */
enum class BoundaryAdvance {
    /** Stay put. */
    None,

    /** Move to the next resource and land at its top. */
    Forward,

    /** Move to the previous resource and land at its *bottom*. */
    Backward,
}

/** What a stalled auto-scroll tick means. */
enum class AutoScrollStall {
    /** Cross into the next resource and keep scrolling. Continuous only. */
    AdvanceResource,

    /** Stop the ticker — there is nothing after this. */
    EndOfBook,
}

/**
 * The rule that makes Continuous *continuous* on a host whose renderer paginates per resource.
 *
 * Android's Continuous mode stacks several chapters' WebViews inside one `ContinuousReaderView`,
 * so a chapter boundary is not an event there at all — the reader simply scrolls through it.
 * Readium-Swift's `EPUBNavigatorViewController` has no such mode: in `scroll` mode it renders
 * exactly one resource and stops at its end, which is Vertical's behaviour. That is why Vertical
 * and Continuous were indistinguishable on iOS — the orientation reached
 * `EpubOrientationMapper.epubScrollMode` and both answered `true`.
 *
 * This is the missing half. Given the live [NavigatorScrollBoundary] (from
 * [ScrollProbes.BOUNDARY_PROBE_JS]) it decides when the reader should cross into the neighbouring
 * resource by itself, so the reader never has to page across a chapter end. Vertical deliberately
 * keeps that page-across — the two modes have to stay distinguishable, and on Android Vertical is
 * the mode where the chapter end is a wall you push through.
 *
 * Stateful because the decision is **edge-triggered**: a boundary that is merely *still* true (the
 * reader is resting at the end of a chapter, reading the last paragraph) must not advance. Only
 * the transition into the boundary does, and then not again until the reader has left it.
 *
 * Not thread-safe; drive it from one collector.
 */
class ContinuousBoundaryAdvancePolicy(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var sawForward = false
    private var sawBackward = false
    // Null rather than a sentinel: `nowMs - Long.MIN_VALUE` overflows back to a negative number,
    // which reads as "still inside the cooldown" and disables the policy for the whole session.
    private var lastAdvanceMs: Long? = null

    /**
     * @param orientation the *effective* reading orientation.
     * @param boundary the live probe result.
     * @param canGoForward false at the last resource of the publication.
     * @param canGoBackward false at the first resource.
     * @param nowMs a monotonic clock reading.
     */
    fun decide(
        orientation: ReaderOrientation,
        boundary: NavigatorScrollBoundary,
        canGoForward: Boolean,
        canGoBackward: Boolean,
        nowMs: Long,
    ): BoundaryAdvance {
        if (orientation != ReaderOrientation.Continuous) {
            // Suppress the edges too: flipping to Vertical mid-book and back must not let a
            // boundary that was already true when the mode changed count as a fresh crossing.
            suppressUntilTheReaderLeavesTheBoundary()
            return BoundaryAdvance.None
        }
        // A resource shorter than the viewport reports BOTH boundaries at once and never leaves
        // either, so an edge-trigger alone would not save us: the first probe after it loads is a
        // rising edge on both. Advancing there would walk the whole publication in one frame —
        // a copyright page, a part-title page and a short dedication in a row is enough. The
        // reader crosses those with an ordinary page turn instead.
        if (boundary.atForwardBoundary && boundary.atBackwardBoundary) {
            sawForward = true
            sawBackward = true
            return BoundaryAdvance.None
        }

        val risingForward = boundary.atForwardBoundary && !sawForward
        val risingBackward = boundary.atBackwardBoundary && !sawBackward
        sawForward = boundary.atForwardBoundary
        sawBackward = boundary.atBackwardBoundary

        // The advance itself lands the reader at a boundary of the NEW resource (top going
        // forward, bottom going backward), and the probe that follows would read as another
        // rising edge. The cooldown is what stops that from cascading.
        lastAdvanceMs?.let { if (nowMs - it < cooldownMs) return BoundaryAdvance.None }

        return when {
            risingForward && canGoForward -> {
                lastAdvanceMs = nowMs
                BoundaryAdvance.Forward
            }
            risingBackward && canGoBackward -> {
                lastAdvanceMs = nowMs
                BoundaryAdvance.Backward
            }
            else -> BoundaryAdvance.None
        }
    }

    /**
     * Treat both boundaries as already seen, so nothing advances until the reader has genuinely
     * scrolled off a boundary and back onto it.
     *
     * Call after a navigation the reader did **not** cause by scrolling — a TOC tap, a bookmark
     * jump, a chapter-map segment. Those land at the top of a resource, which is a boundary; the
     * naive "forget the edges" reset would read that landing as a rising backward edge and
     * immediately bounce the reader into the chapter *before* the one they asked for.
     */
    fun suppressUntilTheReaderLeavesTheBoundary() {
        sawForward = true
        sawBackward = true
    }

    companion object {
        /**
         * Long enough to cover Readium's own resource load and first layout, so the settle after
         * an advance is not read as the next crossing. Short enough that a reader who scrolls
         * straight through a one-screen chapter is not blocked at its end.
         */
        const val DEFAULT_COOLDOWN_MS: Long = 700L
    }
}

/**
 * What auto-scroll should do when a tick moved nothing.
 *
 * Vertical stops — the same thing Android's Vertical does, because the chapter end is a wall
 * there. Continuous crosses into the next resource and keeps going, which is the behaviour a
 * reader gets on Android for free because there is no resource boundary in that view at all.
 * Collapsing the two (which is what iOS did: `if (!scrollByPx(px)) dispatch(ReachedEndOfBook)`)
 * stops hands-free reading at every chapter end.
 */
fun autoScrollStallAction(
    orientation: ReaderOrientation,
    canGoForward: Boolean,
): AutoScrollStall =
    if (orientation == ReaderOrientation.Continuous && canGoForward) {
        AutoScrollStall.AdvanceResource
    } else {
        AutoScrollStall.EndOfBook
    }

/**
 * Index of [href] in [spineHrefs], comparing with the shared href normalisation so a locator's
 * percent-encoded or `./`-prefixed href still matches the reading-order entry it came from.
 * `-1` when it is not in the reading order.
 */
fun spineIndexOfHref(spineHrefs: List<String>, href: String): Int {
    val target = normalizeEpubHref(href)
    return spineHrefs.indexOfFirst { normalizeEpubHref(it) == target }
}
