package com.riffle.core.domain

object ReadingSpeedTracker {

    const val DEFAULT_SECS_PER_POSITION = 63.0

    private const val MIN_SESSION_SEC = 30.0
    private const val MIN_POSITIONS_DELTA = 0.5
    private const val ALPHA = 0.2
    private const val WORDS_PER_POSITION = 250.0
    private const val MIN_WPM = 20.0
    private const val MAX_WPM = 1000.0

    fun recordSession(
        progressDelta: Float,
        timeDeltaSec: Double,
        totalPositions: Float,
        priorSecPerPosition: Double,
    ): Double? {
        if (timeDeltaSec < MIN_SESSION_SEC) return null
        val positionsDelta = progressDelta * totalPositions
        if (positionsDelta < MIN_POSITIONS_DELTA) return null
        val observedRate = timeDeltaSec / positionsDelta
        val impliedWpm = (WORDS_PER_POSITION / observedRate) * 60.0
        if (impliedWpm < MIN_WPM || impliedWpm > MAX_WPM + 0.001) return null
        return ALPHA * observedRate + (1.0 - ALPHA) * priorSecPerPosition
    }
}
