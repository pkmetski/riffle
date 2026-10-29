package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.riffle.core.common.Clock

class ReadingSpeedSession(
    private val clock: Clock,
    private val readingSpeedStore: ReadingSpeedStore,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
) {
    private var sessionStartProgression: Float? = null
    private var sessionStartMs: Long = 0L

    fun onResumed(initialTotalProgression: Float?) {
        sessionStartProgression = initialTotalProgression
        sessionStartMs = clock.nowMs()
    }

    fun onClosed(currentTotalProgression: Float?, totalPositions: Float) {
        if (!enabled()) return
        val startProg = sessionStartProgression ?: return
        sessionStartProgression = null
        val timeDeltaSec = (clock.nowMs() - sessionStartMs) / 1000.0
        val totalProg = currentTotalProgression ?: return
        if (totalPositions == 0f) return
        val progressDelta = totalProg - startProg
        scope.launch {
            val prior = readingSpeedStore.speedSecPerPosition.first()
            val updated = ReadingSpeedTracker.recordSession(progressDelta, timeDeltaSec, totalPositions, prior)
            if (updated != null) readingSpeedStore.updateSpeed(updated)
        }
    }
}
