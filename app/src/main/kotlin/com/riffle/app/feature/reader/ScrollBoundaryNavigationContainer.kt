package com.riffle.app.feature.reader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

class ScrollBoundaryNavigationContainer(context: Context) : FrameLayout(context) {

    var isScrollMode: Boolean = false
    var currentProgression: Float = 0f
    var onNavigateForward: (() -> Unit)? = null
    var onNavigateBackward: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                handleFling(velocityY)
                return false
            }
        },
    )

    // Package-visible so the unit test can call it directly without going through the
    // GestureDetector pipeline, which requires a real event pump to fire reliably.
    internal fun handleFling(velocityY: Float) {
        if (!isScrollMode) return
        when {
            velocityY > 0 && currentProgression >= 1.0f -> onNavigateForward?.invoke()
            velocityY < 0 && currentProgression <= 0.0f -> onNavigateBackward?.invoke()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
