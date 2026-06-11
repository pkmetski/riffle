package com.riffle.app.feature.reader

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.WindowInsetsCompat

/**
 * A left/right edge strip the app claims from the system back gesture, expressed independently of
 * Android's [Rect] so the geometry can be unit-tested without a device.
 */
internal data class EdgeExclusion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Full-height strips over the left/right [systemGestures][WindowInsetsCompat.Type.systemGestures]
 * insets. A side-edge swipe started inside one of these is delivered to the reader (page turn)
 * instead of Android's predictive-back gesture — which, while the reader's system bars are hidden
 * with `BEHAVIOR_DEFAULT`, would reveal the bars and kick the reader out of immersive mode.
 *
 * Only the *side* edges are excluded; top/bottom system gestures (status-bar / nav-bar reveal,
 * home) are left to the system. Returns empty when the gesture insets are zero (3-button nav has
 * no side back-gesture) or the view has no size yet.
 */
internal fun computeSideEdgeExclusions(
    width: Int,
    height: Int,
    leftGestureInset: Int,
    rightGestureInset: Int,
): List<EdgeExclusion> {
    if (width <= 0 || height <= 0) return emptyList()
    return buildList {
        if (leftGestureInset > 0) add(EdgeExclusion(0, 0, leftGestureInset, height))
        if (rightGestureInset > 0) add(EdgeExclusion(width - rightGestureInset, 0, width, height))
    }
}

/**
 * Applies [computeSideEdgeExclusions] to this view's [View.setSystemGestureExclusionRects] when
 * [enabled], otherwise clears it. No-op below API 29, where there is no side-edge system gesture.
 * The OS's 200dp-per-edge exclusion cap does not apply while the reader is in immersive mode
 * (system bars hidden), so the full-height strips take effect.
 */
internal fun View.applySideEdgeGestureExclusion(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val gestures = if (enabled) {
        rootWindowInsets
            ?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
            ?.getInsets(WindowInsetsCompat.Type.systemGestures())
    } else {
        null
    }
    val rects = computeSideEdgeExclusions(
        width = width,
        height = height,
        leftGestureInset = gestures?.left ?: 0,
        rightGestureInset = gestures?.right ?: 0,
    ).map { Rect(it.left, it.top, it.right, it.bottom) }
    if (systemGestureExclusionRects != rects) systemGestureExclusionRects = rects
}
