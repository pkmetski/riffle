package com.riffle.app.feature.readersettings

/**
 * The granular playback-speed range shared by the Read Aloud mini-player and the full-screen
 * Audiobook player: any [STEP] multiple in [[MIN], [MAX]] (so 1.4× is reachable). Both players
 * talk to the same ABS audio file, so keeping one range/snap rule here means they behave identically.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 3.0f
    const val STEP = 0.05f

    /** Quick-jump presets surfaced as options in Settings and the sheet. */
    val PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Clamps to [[MIN], [MAX]] and snaps to the nearest [STEP], free of float drift. */
    fun snap(raw: Float): Float = (Math.round(raw / STEP) * STEP).coerceIn(MIN, MAX)

    /** Formats the speed as 1×, 1.25×, 1.4×, 0.75× … (rounded to 0.05, trailing zeros trimmed). */
    fun label(speed: Float): String {
        val rounded = Math.round(speed * 100.0) / 100.0
        val s = if (rounded % 1.0 == 0.0) rounded.toInt().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
        return "${s}×"
    }
}
