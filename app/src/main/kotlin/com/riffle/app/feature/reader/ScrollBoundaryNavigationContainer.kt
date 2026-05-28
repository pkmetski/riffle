package com.riffle.app.feature.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class ScrollBoundaryNavigationContainer(context: Context) : FrameLayout(context) {

    var isScrollMode: Boolean = false

    // Driven by a JS-driven poll in EpubReaderScreen so boundary state reflects the WebView's
    // actual scroll position rather than Readium's locator progression value (which keeps
    // emitting during touch even when scroll position is unchanged at a boundary).
    var atForwardBoundary: Boolean = false
    var atBackwardBoundary: Boolean = false

    var onNavigateForward: (() -> Unit)? = null
    var onNavigateBackward: (() -> Unit)? = null
    // Two-channel feedback API. Visibility binds to the started/ended pair; the circle's
    // fill binds to onPullProgress, which reports cumulative pull distance — the same value
    // that gates navigation. When the circle hits 1.0, nav fires.
    var onPullStarted: ((forward: Boolean) -> Unit)? = null
    var onPullEnded: (() -> Unit)? = null
    var onPullProgress: ((progress: Float) -> Unit)? = null

    private var lastNavigationMs = 0L
    private var lastVolumeNavMs = 0L
    private var lastTouchY = 0f
    private var lastMoveTimeMs = 0L
    private var pullDirection = 0 // 1 = forward pull, -1 = backward pull, 0 = inactive
    private var pullDistancePx = 0f
    private val density = context.resources.displayMetrics.density
    private val pullDistanceThresholdPx = PULL_DISTANCE_THRESHOLD_DP * density
    private val maxFillRatePxPerMs = MAX_FILL_RATE_DP_PER_MS * density
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var sawHorizontalMotion = false
    private var swallowingUp = false
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

    // Called by the volume key handler. atBoundary is computed by the caller via JS so it
    // reflects the WebView's actual scroll position rather than Readium's progression value.
    internal fun handleVolumeScroll(forward: Boolean, atBoundary: Boolean, evaluateJs: (String) -> Unit) {
        if (!isScrollMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVolumeNavMs < VOLUME_NAV_COOLDOWN_MS) return
        if (forward) {
            if (atBoundary) {
                lastVolumeNavMs = now
                onNavigateForward?.invoke()
            } else {
                evaluateJs("window.scrollBy({top: window.innerHeight * 0.8, behavior: 'smooth'})")
            }
        } else {
            if (atBoundary) {
                lastVolumeNavMs = now
                onNavigateBackward?.invoke()
            } else {
                evaluateJs("window.scrollBy({top: -(window.innerHeight * 0.8), behavior: 'smooth'})")
            }
        }
    }

    // R2WebView triggers chapter navigation from ACTION_UP when its mIsBeingDragged flag is
    // set (xDiff > touchSlop at some point) AND the final |yDelta| < 200 px. We let every
    // move through so vertical scrolling and flings work normally, then steal ACTION_UP iff
    // R2WebView would have navigated on it. The WebView receives ACTION_CANCEL instead,
    // which clears its drag flag without invoking the nav branch (R2WebView.kt:785-788).
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isScrollMode) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = ev.x
                gestureStartY = ev.y
                sawHorizontalMotion = false
                swallowingUp = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sawHorizontalMotion && abs(ev.x - gestureStartX) > touchSlopPx) {
                    sawHorizontalMotion = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val dy = abs(ev.y - gestureStartY)
                if (sawHorizontalMotion && dy < NAV_Y_DELTA_THRESHOLD_PX) {
                    swallowingUp = true
                    return true
                }
            }
        }
        return false
    }

    // Consumed only when we intercepted ACTION_UP above. Otherwise transparent.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val consume = swallowingUp
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            swallowingUp = false
            sawHorizontalMotion = false
        }
        return consume
    }

    // R2WebView calls requestDisallowInterceptTouchEvent(true) on every ACTION_MOVE when the
    // initial touch is within 30 px of either edge (the gutter). Honoring that would let an
    // edge-started horizontal swipe bypass our intercept entirely and reach the WebView's
    // chapter-nav path. In scroll mode we never want to give up the right to intercept.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (isScrollMode && disallowIntercept) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isScrollMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = ev.y
                    resetPull()
                }
                MotionEvent.ACTION_MOVE -> handlePullMove(ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetPull()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handlePullMove(y: Float) {
        val dy = y - lastTouchY
        lastTouchY = y
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavigationMs < NAVIGATION_COOLDOWN_MS) return

        // Direction reversal cancels an active pull; the user is no longer trying to push
        // through the boundary.
        if (pullDirection == 1 && dy > MOVEMENT_SLOP_PX) {
            resetPull(); return
        }
        if (pullDirection == -1 && dy < -MOVEMENT_SLOP_PX) {
            resetPull(); return
        }

        // If the WebView left the boundary mid-pull (e.g. the JS poll detected scroll
        // headroom), the user is no longer wedged against the wall.
        if (pullDirection == 1 && !atForwardBoundary) {
            resetPull(); return
        }
        if (pullDirection == -1 && !atBackwardBoundary) {
            resetPull(); return
        }

        // Start a new pull only when finger moves in the correct direction at a boundary.
        if (pullDirection == 0) {
            when {
                dy < -MOVEMENT_SLOP_PX && atForwardBoundary -> {
                    pullDirection = 1
                    pullDistancePx = 0f
                    lastMoveTimeMs = now
                    onPullStarted?.invoke(true)
                    onPullProgress?.invoke(0f)
                }
                dy > MOVEMENT_SLOP_PX && atBackwardBoundary -> {
                    pullDirection = -1
                    pullDistancePx = 0f
                    lastMoveTimeMs = now
                    onPullStarted?.invoke(false)
                    onPullProgress?.invoke(0f)
                }
                else -> return
            }
        }

        // Accumulate physical pull distance, capped by a maximum fill rate. The cap is
        // what makes a sudden swipe unable to trigger nav: even if the finger covers the
        // threshold distance in 150 ms, the circle only fills by maxFillRate * 150 ms.
        // Deliberate slow pulls fall below the cap and accumulate at their natural rate.
        val deltaMs = (now - lastMoveTimeMs).coerceIn(0L, 100L)
        lastMoveTimeMs = now
        val maxAddPx = maxFillRatePxPerMs * deltaMs
        when (pullDirection) {
            1 -> if (dy < 0) pullDistancePx += (-dy).coerceAtMost(maxAddPx)
            -1 -> if (dy > 0) pullDistancePx += dy.coerceAtMost(maxAddPx)
        }
        val distanceProgress = (pullDistancePx / pullDistanceThresholdPx).coerceIn(0f, 1f)
        onPullProgress?.invoke(distanceProgress)

        if (pullDistancePx >= pullDistanceThresholdPx) {
            val wasForward = pullDirection == 1
            lastNavigationMs = now
            pullDirection = 0
            pullDistancePx = 0f
            onPullEnded?.invoke()
            Handler(Looper.getMainLooper()).post {
                if (wasForward) onNavigateForward?.invoke() else onNavigateBackward?.invoke()
            }
        }
    }

    private fun resetPull() {
        if (pullDirection != 0) onPullEnded?.invoke()
        pullDirection = 0
        pullDistancePx = 0f
        lastMoveTimeMs = 0L
    }

    companion object {
        internal const val NAVIGATION_COOLDOWN_MS = 1500L
        // Pixels of finger movement that count as a deliberate pull direction. Small enough
        // to start the pull on any clear motion; large enough to ignore touch jitter.
        internal const val MOVEMENT_SLOP_PX = 2f
        // Physical pull distance (in dp) past a chapter boundary that triggers navigation.
        // Doubles as the circle's full-fill threshold — so the visible feedback (full circle)
        // and the trigger condition (nav fires) coincide.
        internal const val PULL_DISTANCE_THRESHOLD_DP = 160f
        // Maximum rate at which the pull-distance accumulator can grow, in dp per ms.
        // 0.25 dp/ms = 250 dp/sec. A typical fling is ~1500-3000 dp/sec; deliberate pulls
        // sit around 200-500 dp/sec. Capping at 250 dp/sec means a fling fills the circle
        // far slower than the finger moves, so the gesture ends before the threshold is met.
        internal const val MAX_FILL_RATE_DP_PER_MS = 0.25f
        // Short cooldown for volume key presses — just long enough to absorb OS key-repeat
        // events. Kept separate from NAVIGATION_COOLDOWN_MS so a preceding chapter transition
        // never swallows a deliberate button press.
        internal const val VOLUME_NAV_COOLDOWN_MS = 300L
        // Mirrors R2WebView's hardcoded "navigate only if |yDelta| < 200 px" threshold
        // (R2WebView.kt:746). Used to decide whether to swallow ACTION_UP.
        internal const val NAV_Y_DELTA_THRESHOLD_PX = 200
    }
}
