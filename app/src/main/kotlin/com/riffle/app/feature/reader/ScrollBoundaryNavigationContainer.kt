package com.riffle.app.feature.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.riffle.feature.reader.PullBoundaryGestureState
import kotlin.math.abs

class ScrollBoundaryNavigationContainer(context: Context) : FrameLayout(context) {

    var isScrollMode: Boolean = false

    // Driven by a JS-driven poll in EpubReaderScreen so boundary state reflects the WebView's
    // actual scroll position rather than Readium's locator progression value (which keeps
    // emitting during touch even when scroll position is unchanged at a boundary).
    var atForwardBoundary: Boolean = false
    var atBackwardBoundary: Boolean = false

    // Whether a chapter actually exists past the current one. Set by EpubReaderScreen from the
    // reading-order position. At the very first/last chapter there is nowhere to navigate, so we
    // never arm a pull there — no pill, no feedback for a gesture that could never complete.
    var canNavigateForward: Boolean = true
    var canNavigateBackward: Boolean = true

    var onNavigateForward: (() -> Unit)? = null
    var onNavigateBackward: (() -> Unit)? = null
    var onPullStarted: ((forward: Boolean) -> Unit)? = null
    var onPullEnded: (() -> Unit)? = null
    var onPullProgress: ((progress: Float) -> Unit)? = null

    /**
     * Fires on ACTION_DOWN for every touch on the reader surface, regardless of reader mode. Used
     * by the ViewModel to disarm the post-resume position-restore watcher — see
     * [com.riffle.app.feature.reader.session.ResumeRestorer.onUserInteracted].
     */
    var onUserTouch: (() -> Unit)? = null

    /**
     * Fires once per gesture the first time vertical movement exceeds touch-slop in scroll mode.
     * Used to enter immersive mode at scroll start rather than waiting for a Readium locator event.
     */
    var onScrollStart: (() -> Unit)? = null

    private var lastVolumeNavMs = 0L
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var scrollStartFired = false
    private val density = context.resources.displayMetrics.density
    private val gestureState = PullBoundaryGestureState(density).also { gs ->
        gs.onPullStarted = { fwd -> onPullStarted?.invoke(fwd) }
        gs.onPullEnded = { onPullEnded?.invoke() }
        gs.onPullProgress = { p -> onPullProgress?.invoke(p) }
        gs.onNavigate = { fwd ->
            Handler(Looper.getMainLooper()).post {
                if (fwd) onNavigateForward?.invoke() else onNavigateBackward?.invoke()
            }
        }
    }
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
                evaluateJs("window.scrollBy({top: window.innerHeight * $VOLUME_SCROLL_FRACTION, behavior: 'smooth'})")
            }
        } else {
            if (atBoundary) {
                lastVolumeNavMs = now
                onNavigateBackward?.invoke()
            } else {
                evaluateJs("window.scrollBy({top: -(window.innerHeight * $VOLUME_SCROLL_FRACTION), behavior: 'smooth'})")
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
        // Unconditional user-touch signal — fires in all reader modes so the resume-restore
        // watcher can disarm on any user gesture (see [onUserTouch] docstring).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onUserTouch?.invoke()
        if (isScrollMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = ev.y
                    downX = ev.x
                    downY = ev.y
                    scrollStartFired = false
                    resetPull()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = abs(ev.y - downY)
                    if (!scrollStartFired && dy > touchSlopPx && dy >= abs(ev.x - downX)) {
                        scrollStartFired = true
                        onScrollStart?.invoke()
                    }
                    handlePullMove(ev.x, ev.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollStartFired = false
                    resetPull()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handlePullMove(x: Float, y: Float) {
        val dy = y - lastTouchY
        lastTouchY = y
        val now = SystemClock.elapsedRealtime()
        // Only up/down pulls should trigger chapter navigation in scroll mode. A horizontal
        // swipe has tiny y jitter that easily passes MOVEMENT_SLOP_PX; guard before delegating.
        val totalDx = x - downX
        val totalDy = y - downY
        if (abs(totalDx) >= abs(totalDy)) return
        gestureState.onMove(dy, atForwardBoundary, atBackwardBoundary,
            canNavigateForward, canNavigateBackward, now)
    }

    private fun resetPull() {
        gestureState.reset()
    }

    companion object {
        // Pull gesture thresholds — delegate to the shared state machine so both platforms
        // use identical values. Exposed here so existing tests can reference them via this class.
        internal val NAVIGATION_COOLDOWN_MS: Long get() = PullBoundaryGestureState.NAVIGATION_COOLDOWN_MS
        internal val MOVEMENT_SLOP_PX: Float get() = PullBoundaryGestureState.MOVEMENT_SLOP_PX
        internal val PULL_DISTANCE_THRESHOLD_DP: Float get() = PullBoundaryGestureState.PULL_DISTANCE_THRESHOLD_DP
        internal val MAX_FILL_RATE_DP_PER_MS: Float get() = PullBoundaryGestureState.MAX_FILL_RATE_DP_PER_MS
        internal const val VOLUME_NAV_COOLDOWN_MS = 300L
        internal const val VOLUME_SCROLL_FRACTION = 0.9f
        internal const val NAV_Y_DELTA_THRESHOLD_PX = 200
    }
}
