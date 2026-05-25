package com.riffle.app.feature.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
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
    private var lastVolumeScrollProgression = Float.NaN
    private var lastVolumeNavMs = 0L
    private var lastTouchY = 0f
    private var dragAccum = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var isIntercepting = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Defer to avoid invoking navigation during active touch event dispatch,
                // which can interfere with the WebView's in-progress gesture handling.
                post { handleFling(velocityX, velocityY) }
                return false
            }
        },
    )

    internal fun handleFling(velocityX: Float, velocityY: Float) {
        if (!isScrollMode) return
        // Ignore diagonal or predominantly horizontal flings — those belong to the ViewPager.
        if (abs(velocityX) >= abs(velocityY)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavigationMs < NAVIGATION_COOLDOWN_MS) return
        when {
            // Finger moves up (negative velocityY) = scrolling forward through content.
            velocityY < 0 && currentProgression >= FLING_FORWARD_THRESHOLD -> {
                lastNavigationMs = now
                dragAccum = 0f
                onNavigateForward?.invoke()
            }
            // Finger moves down (positive velocityY) = scrolling backward through content.
            velocityY > 0 && currentProgression <= FLING_BACKWARD_THRESHOLD -> {
                lastNavigationMs = now
                dragAccum = 0f
                onNavigateBackward?.invoke()
            }
        }
    }

    // Called by the volume key handler. In scroll mode: if already at the chapter boundary,
    // navigate to the adjacent chapter; otherwise trigger a smooth CSS scroll. The WebView
    // stops naturally at the chapter end, so the next key press at progression ≈ 1.0 / 0.0
    // will navigate instead of scrolling — giving the desired "sticky boundary" feel.
    internal fun handleVolumeScroll(forward: Boolean, evaluateJs: (String) -> Unit) {
        if (!isScrollMode) return
        val now = SystemClock.elapsedRealtime()
        // Use a short per-button cooldown to absorb OS key-repeat events, but keep this
        // independent of lastNavigationMs so a prior fling or chapter navigation doesn't
        // swallow the user's deliberate next button press.
        if (now - lastVolumeNavMs < VOLUME_NAV_COOLDOWN_MS) return
        if (forward) {
            // Navigate if explicitly at the boundary threshold, or if the last scroll attempt
            // didn't change progression (WebView was already stuck at the chapter end).
            val atBoundary = currentProgression >= VOLUME_FORWARD_THRESHOLD ||
                currentProgression == lastVolumeScrollProgression
            if (atBoundary) {
                lastVolumeNavMs = now
                lastVolumeScrollProgression = Float.NaN
                onNavigateForward?.invoke()
            } else {
                lastVolumeScrollProgression = currentProgression
                evaluateJs("window.scrollBy({top: window.innerHeight * 0.8, behavior: 'smooth'})")
            }
        } else {
            val atBoundary = currentProgression <= VOLUME_BACKWARD_THRESHOLD ||
                currentProgression == lastVolumeScrollProgression
            if (atBoundary) {
                lastVolumeNavMs = now
                lastVolumeScrollProgression = Float.NaN
                onNavigateBackward?.invoke()
            } else {
                lastVolumeScrollProgression = currentProgression
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
                                if (dragAccum >= DRAG_THRESHOLD_PX) {
                                    lastNavigationMs = now
                                    dragAccum = 0f
                                    Handler(Looper.getMainLooper()).post { onNavigateForward?.invoke() }
                                }
                            }
                            dy > 0 && currentProgression <= DRAG_BACKWARD_THRESHOLD && progressionStale -> {
                                dragAccum += dy
                                if (dragAccum >= DRAG_THRESHOLD_PX) {
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
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        internal const val NAVIGATION_COOLDOWN_MS = 1500L
        // Fling threshold: raised to 90% to match drag, requiring the user to be close to
        // the chapter boundary before an upward/downward fling crosses chapters.
        internal const val FLING_FORWARD_THRESHOLD = 0.90f
        internal const val FLING_BACKWARD_THRESHOLD = 0.10f
        // Drag thresholds are tighter since drag relies on progression going stale
        // (WebView truly stuck at the boundary), so false positives are less likely.
        internal const val DRAG_FORWARD_THRESHOLD = 0.90f
        internal const val DRAG_BACKWARD_THRESHOLD = 0.10f
        // Progression must not have changed for this long before drag-navigation activates.
        // At 60 fps the locator fires every ~16 ms, so 300 ms = ~18 missed frames = clearly stuck.
        internal const val STALE_PROGRESSION_MS = 300L
        // Pixels of drag past the stuck boundary before navigation fires.
        internal const val DRAG_THRESHOLD_PX = 80f
        // Volume key thresholds: generous enough that the visible end-of-chapter (where
        // Readium may report < 1.0) triggers navigation in one press rather than two.
        internal const val VOLUME_FORWARD_THRESHOLD = 0.95f
        internal const val VOLUME_BACKWARD_THRESHOLD = 0.05f
        // Short cooldown for volume key presses — just long enough to absorb OS key-repeat
        // events. Kept separate from NAVIGATION_COOLDOWN_MS so a preceding fling or
        // chapter transition never swallows a deliberate button press.
        internal const val VOLUME_NAV_COOLDOWN_MS = 300L
    }
}
