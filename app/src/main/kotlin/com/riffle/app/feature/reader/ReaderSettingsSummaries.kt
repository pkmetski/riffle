package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import java.time.LocalTime
import kotlin.math.roundToInt

fun ReaderTheme.label(): String = when (this) {
    ReaderTheme.Light -> "Light"
    ReaderTheme.Dark -> "Dark"
    ReaderTheme.DarkDim -> "Dim"
    ReaderTheme.Sepia -> "Sepia"
    ReaderTheme.Auto -> "Auto"
}

fun ReaderFontFamily.label(): String = when (this) {
    ReaderFontFamily.Original -> "Original"
    ReaderFontFamily.Serif -> "Serif"
    ReaderFontFamily.SansSerif -> "Sans serif"
    ReaderFontFamily.Monospace -> "Mono"
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    ReaderFontFamily.OpenDyslexic -> "Dyslexic"
}

fun lineSpacingWord(value: Float): String = when {
    value < 1.15f -> "Tight"
    value < 1.35f -> "Compact"
    value < 1.55f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 1.95f -> "Roomy"
    else -> "Spacious"
}

fun marginsWord(value: Float): String = when {
    value < 0.5f -> "Edge"
    value < 0.85f -> "Tight"
    value < 1.25f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 2.35f -> "Roomy"
    else -> "Wide"
}

fun formattingSummary(prefs: FormattingPreferences): String =
    "${prefs.fontFamily.label()} · ${(prefs.fontSize * 100).roundToInt()}% · ${marginsWord(prefs.margins)} margins"

fun displaySummary(prefs: FormattingPreferences): String {
    val mode = when (prefs.orientation) {
        ReaderOrientation.Horizontal -> "Paginated"
        ReaderOrientation.Vertical -> "Scroll"
        ReaderOrientation.Continuous -> "Continuous"
    }
    val map = if (prefs.showChapterMap) "map on" else "map off"
    return "${prefs.theme.label()} · $mode · $map"
}

fun behaviorSummary(keepScreenOn: Boolean, volumeKeyNavigationEnabled: Boolean): String =
    "Keep screen ${if (keepScreenOn) "on" else "off"} · volume nav ${if (volumeKeyNavigationEnabled) "on" else "off"}"

fun autoScheduleSummary(schedule: ThemeSchedule): String {
    fun t(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)
    return "Day ${t(schedule.dayStart)} · ${schedule.dayTheme.label()} → " +
        "Night ${t(schedule.nightStart)} · ${schedule.nightTheme.label()}"
}
