package com.riffle.core.domain.autoscroll

@JvmInline
value class AutoScrollSpeed private constructor(val wpm: Int) {

    fun nudge(by: Int): AutoScrollSpeed = of(wpm + by)

    companion object {
        const val MIN_WPM: Int = 80
        const val MAX_WPM: Int = 600
        const val STEP_WPM: Int = 10
        const val DEFAULT_WPM: Int = 250

        val Default: AutoScrollSpeed = AutoScrollSpeed(DEFAULT_WPM)

        fun of(wpm: Int): AutoScrollSpeed {
            val clamped = wpm.coerceIn(MIN_WPM, MAX_WPM)
            val snapped = ((clamped + STEP_WPM / 2) / STEP_WPM) * STEP_WPM
            return AutoScrollSpeed(snapped.coerceIn(MIN_WPM, MAX_WPM))
        }
    }
}
