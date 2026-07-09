package com.riffle.app.feature.reader

/**
 * Merges consecutive volume-key page-scroll presses that land while a smooth-scroll animation is
 * still in flight. Without coalescing, each new press restarts the animation from the current
 * (still-animating) scroll position, so a run of quick taps stutters instead of gliding — the
 * jankier feel that separates continuous mode from vertical mode's Chromium `behavior: 'smooth'`
 * scroll (which coalesces natively).
 *
 * Not thread-safe; expected to be called from the main thread.
 *
 * @param validityWindowMs how long a computed target stays "in flight" — should match the smooth-
 *   scroll animation duration passed to the port. A press arriving inside this window extends the
 *   previous target; a press arriving after resets to the current scroll position.
 */
internal class PageScrollCoalescer(private val validityWindowMs: Long) {

    private var pendingTargetY: Int = 0
    private var validUntilMs: Long = Long.MIN_VALUE

    /**
     * Absolute scroll target for a new page-scroll press. If a previous press is still in flight
     * (elapsed since it was computed < [validityWindowMs]), extends that target by [dy]; otherwise
     * bases the target on [currentScrollY].
     */
    fun computeTarget(currentScrollY: Int, dy: Int, nowMs: Long): Int {
        val base = if (nowMs < validUntilMs) pendingTargetY else currentScrollY
        val target = base + dy
        pendingTargetY = target
        validUntilMs = nowMs + validityWindowMs
        return target
    }

    /** Drop any pending target — e.g. the user touched the view so a manual scroll takes over. */
    fun reset() {
        validUntilMs = Long.MIN_VALUE
    }
}
