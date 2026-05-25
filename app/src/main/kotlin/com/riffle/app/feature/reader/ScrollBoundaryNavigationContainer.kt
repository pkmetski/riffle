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

    // Every assignment updates progressionLastChangedMs so we can detect when the
    // WebView stops scrolling (its locator stops emitting = we're at the scroll boundary).
    var currentProgression: Float = 0f
        set(value) {
            progressionLastChangedMs = SystemClock.elapsedRealtime()
            field = value
        }

    var onNavigateForward: (() -> Unit)? = null
    var onNavigateBackward: (() -> Unit)? = null

    private var lastNavigationMs = 0L
    private var progressionLastChangedMs = 0L
    private var lastVolumeNavMs = 0L
    private var lastTouchY = 0f
    private var dragAccum = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var isIntercepting = false
    // Density-independent drag threshold: 120dp converted to pixels at runtime so the
    // required pull distance is consistent across screen densities.
    private val dragThresholdPx = DRAG_THRESHOLD_DP * context.resources.displayMetrics.density

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

    // Intercept horizontal gestures in scroll mode so they never reach the ViewPager
    // descendant inside EpubNavigatorFragment. Android automatically sends ACTION_CANCEL
    // to any child that already received ACTION_DOWN when this returns true.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isScrollMode) return false
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = abs(ev.x - gestureStartX)
            val dy = abs(ev.y - gestureStartY)
            if (!isIntercepting && dx > touchSlop && dx > dy) {
                isIntercepting = true
                return true
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            isIntercepting = false
        }
        return isIntercepting
    }

    // Consume events that were intercepted as horizontal gestures; pass everything else through.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            isIntercepting = false
        }
        return isIntercepting
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isScrollMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = ev.x
                    gestureStartY = ev.y
                    lastTouchY = ev.y
                    dragAccum = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - lastTouchY
                    lastTouchY = ev.y
                    val now = SystemClock.elapsedRealtime()
                    // Only accumulate drag when the cooldown has expired and progression has
                    // gone stale — meaning the WebView cannot scroll further in this direction.
                    if (now - lastNavigationMs >= NAVIGATION_COOLDOWN_MS) {
                        val progressionStale = now - progressionLastChangedMs > STALE_PROGRESSION_MS
                        when {
                            dy < 0 && currentProgression >= DRAG_FORWARD_THRESHOLD && progressionStale -> {
                                dragAccum -= dy // dy is negative; -dy accumulates positive distance
                                if (dragAccum >= dragThresholdPx) {
                                    lastNavigationMs = now
                                    dragAccum = 0f
                                    Handler(Looper.getMainLooper()).post { onNavigateForward?.invoke() }
                                }
                            }
                            dy > 0 && currentProgression <= DRAG_BACKWARD_THRESHOLD && progressionStale -> {
                                dragAccum += dy
                                if (dragAccum >= dragThresholdPx) {
                                    lastNavigationMs = now
                                    dragAccum = 0f
                                    Handler(Looper.getMainLooper()).post { onNavigateBackward?.invoke() }
                                }
                            }
                            else -> dragAccum = 0f
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragAccum = 0f
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        internal const val NAVIGATION_COOLDOWN_MS = 1500L
        // Drag thresholds: drag navigation only activates when the WebView is truly stuck
        // at the boundary (progression stale) and the user pulls deliberately past it.
        internal const val DRAG_FORWARD_THRESHOLD = 0.90f
        internal const val DRAG_BACKWARD_THRESHOLD = 0.10f
        // Progression must not have changed for this long before drag-navigation activates.
        // At 60 fps the locator fires every ~16 ms, so 300 ms = ~18 missed frames = clearly stuck.
        internal const val STALE_PROGRESSION_MS = 300L
        // Required pull distance in dp. Converted to pixels at construction time so it is
        // consistent across screen densities. 120dp requires a clearly intentional drag.
        internal const val DRAG_THRESHOLD_DP = 120f
        // Short cooldown for volume key presses — just long enough to absorb OS key-repeat
        // events. Kept separate from NAVIGATION_COOLDOWN_MS so a preceding fling or
        // chapter transition never swallows a deliberate button press.
        internal const val VOLUME_NAV_COOLDOWN_MS = 300L
    }
}
