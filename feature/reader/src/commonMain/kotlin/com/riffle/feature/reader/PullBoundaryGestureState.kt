package com.riffle.feature.reader

class PullBoundaryGestureState(private val density: Float) {

    private var pullDirection = 0
    private var pullDistancePx = 0f
    private var lastMoveTimeMs = 0L
    private var lastNavigationMs = 0L

    private val pullDistanceThresholdPx = PULL_DISTANCE_THRESHOLD_DP * density
    private val maxFillRatePxPerMs = MAX_FILL_RATE_DP_PER_MS * density

    var onPullStarted: ((forward: Boolean) -> Unit)? = null
    var onPullEnded: (() -> Unit)? = null
    var onPullProgress: ((progress: Float) -> Unit)? = null
    var onNavigate: ((forward: Boolean) -> Unit)? = null

    fun onMove(
        dy: Float,
        atForwardBoundary: Boolean,
        atBackwardBoundary: Boolean,
        canNavigateForward: Boolean,
        canNavigateBackward: Boolean,
        nowMs: Long,
    ) {
        if (nowMs - lastNavigationMs < NAVIGATION_COOLDOWN_MS) return

        if (pullDirection == 1 && dy > MOVEMENT_SLOP_PX) { reset(); return }
        if (pullDirection == -1 && dy < -MOVEMENT_SLOP_PX) { reset(); return }

        if (pullDirection == 1 && !atForwardBoundary) { reset(); return }
        if (pullDirection == -1 && !atBackwardBoundary) { reset(); return }

        if (pullDirection == 0) {
            when {
                dy < -MOVEMENT_SLOP_PX && atForwardBoundary && canNavigateForward -> {
                    pullDirection = 1
                    pullDistancePx = 0f
                    lastMoveTimeMs = nowMs
                    onPullStarted?.invoke(true)
                    onPullProgress?.invoke(0f)
                }
                dy > MOVEMENT_SLOP_PX && atBackwardBoundary && canNavigateBackward -> {
                    pullDirection = -1
                    pullDistancePx = 0f
                    lastMoveTimeMs = nowMs
                    onPullStarted?.invoke(false)
                    onPullProgress?.invoke(0f)
                }
                else -> return
            }
        }

        val deltaMs = (nowMs - lastMoveTimeMs).coerceIn(0L, 100L)
        lastMoveTimeMs = nowMs
        val maxAddPx = maxFillRatePxPerMs * deltaMs
        when (pullDirection) {
            1 -> if (dy < 0) pullDistancePx += (-dy).coerceAtMost(maxAddPx)
            -1 -> if (dy > 0) pullDistancePx += dy.coerceAtMost(maxAddPx)
        }
        val distanceProgress = (pullDistancePx / pullDistanceThresholdPx).coerceIn(0f, 1f)
        onPullProgress?.invoke(distanceProgress)

        if (pullDistancePx >= pullDistanceThresholdPx) {
            val wasForward = pullDirection == 1
            lastNavigationMs = nowMs
            pullDirection = 0
            pullDistancePx = 0f
            onPullEnded?.invoke()
            onNavigate?.invoke(wasForward)
        }
    }

    fun reset() {
        if (pullDirection != 0) onPullEnded?.invoke()
        pullDirection = 0
        pullDistancePx = 0f
        lastMoveTimeMs = 0L
    }

    companion object {
        const val NAVIGATION_COOLDOWN_MS = 1500L
        const val MOVEMENT_SLOP_PX = 2f
        const val PULL_DISTANCE_THRESHOLD_DP = 160f
        const val MAX_FILL_RATE_DP_PER_MS = 0.25f
    }
}
