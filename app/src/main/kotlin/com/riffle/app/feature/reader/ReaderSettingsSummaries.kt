package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
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

fun AutoReaderThemeMode.label(): String = when (this) {
    AutoReaderThemeMode.Schedule -> "Time based"
    AutoReaderThemeMode.AppTheme -> "App theme"
}

@Composable
fun ReaderTheme.localizedLabel(): String = when (this) {
    ReaderTheme.Light -> stringResource(R.string.ui_light)
    ReaderTheme.Dark -> stringResource(R.string.ui_dark)
    ReaderTheme.DarkDim -> stringResource(R.string.ui_dim)
    ReaderTheme.Sepia -> stringResource(R.string.ui_sepia)
    ReaderTheme.Auto -> stringResource(R.string.ui_auto)
}

@Composable
fun AutoReaderThemeMode.localizedLabel(): String = when (this) {
    AutoReaderThemeMode.Schedule -> stringResource(R.string.ui_time_based)
    AutoReaderThemeMode.AppTheme -> stringResource(R.string.ui_app_theme)
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

@Composable
fun ReaderFontFamily.localizedLabel(): String = when (this) {
    ReaderFontFamily.Original -> stringResource(R.string.ui_original)
    ReaderFontFamily.Serif -> stringResource(R.string.ui_serif)
    ReaderFontFamily.SansSerif -> stringResource(R.string.ui_sans_serif)
    ReaderFontFamily.Monospace -> stringResource(R.string.ui_mono)
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    ReaderFontFamily.OpenDyslexic -> stringResource(R.string.ui_dyslexic)
}

fun lineSpacingWord(value: Float): String = when {
    value < 1.15f -> "Tight"
    value < 1.35f -> "Compact"
    value < 1.55f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 1.95f -> "Roomy"
    else -> "Spacious"
}

@Composable
fun lineSpacingLabel(value: Float): String = when {
    value < 1.15f -> stringResource(R.string.ui_tight)
    value < 1.35f -> stringResource(R.string.ui_compact)
    value < 1.55f -> stringResource(R.string.ui_normal)
    value < 1.75f -> stringResource(R.string.ui_comfortable)
    value < 1.95f -> stringResource(R.string.ui_roomy)
    else -> stringResource(R.string.ui_spacious)
}

fun marginsWord(value: Float): String = when {
    value < 0.5f -> "Edge"
    value < 0.85f -> "Tight"
    value < 1.25f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 2.35f -> "Roomy"
    else -> "Wide"
}

@Composable
fun marginsLabel(value: Float): String = when {
    value < 0.5f -> stringResource(R.string.ui_edge)
    value < 0.85f -> stringResource(R.string.ui_tight)
    value < 1.25f -> stringResource(R.string.ui_normal)
    value < 1.75f -> stringResource(R.string.ui_comfortable)
    value < 2.35f -> stringResource(R.string.ui_roomy)
    else -> stringResource(R.string.ui_wide)
}

fun formattingSummary(prefs: FormattingPreferences): String =
    "${prefs.fontFamily.label()} · ${(prefs.fontSize * 100).roundToInt()}% · ${marginsWord(prefs.margins)} margins"

@Composable
fun localizedFormattingSummary(prefs: FormattingPreferences): String =
    stringResource(
        R.string.ui_formatting_summary,
        prefs.fontFamily.localizedLabel(),
        (prefs.fontSize * 100).roundToInt(),
        stringResource(R.string.ui_margins_summary, marginsLabel(prefs.margins)),
    )

fun displaySummary(prefs: FormattingPreferences): String {
    val mode = when (prefs.orientation) {
        ReaderOrientation.Horizontal -> "Paginated"
        ReaderOrientation.Vertical -> "Scroll"
        ReaderOrientation.Continuous -> "Continuous"
    }
    val map = if (prefs.showChapterMap) "map on" else "map off"
    val theme = if (prefs.theme == ReaderTheme.Auto) {
        "Auto ${prefs.autoReaderThemeMode.label()}"
    } else {
        prefs.theme.label()
    }
    return "$theme · $mode · $map"
}

@Composable
fun ReaderOrientation.localizedLabel(): String = when (this) {
    ReaderOrientation.Horizontal -> stringResource(R.string.ui_paginated)
    ReaderOrientation.Vertical -> stringResource(R.string.ui_scroll)
    ReaderOrientation.Continuous -> stringResource(R.string.ui_continuous)
}

@Composable
fun localizedDisplaySummary(prefs: FormattingPreferences): String =
    stringResource(
        R.string.ui_display_summary,
        if (prefs.theme == ReaderTheme.Auto) {
            stringResource(R.string.ui_auto_theme_summary, prefs.autoReaderThemeMode.localizedLabel())
        } else {
            prefs.theme.localizedLabel()
        },
        prefs.orientation.localizedLabel(),
        if (prefs.showChapterMap) stringResource(R.string.ui_map_on) else stringResource(R.string.ui_map_off),
    )

fun behaviorSummary(keepScreenOn: Boolean, volumeKeyNavigationEnabled: Boolean): String =
    "Keep screen ${if (keepScreenOn) "on" else "off"} · volume nav ${if (volumeKeyNavigationEnabled) "on" else "off"}"

fun autoScrollSummary(prefs: FormattingPreferences): String =
    if (prefs.showAutoScroll) "Hands-free scroll — ${prefs.autoScrollWpm} wpm" else "Off"

@Composable
fun localizedAutoScrollSummary(prefs: FormattingPreferences): String =
    if (prefs.showAutoScroll) {
        stringResource(R.string.ui_auto_scroll_running_summary, prefs.autoScrollWpm)
    } else {
        stringResource(R.string.ui_off)
    }

fun cadenceSummary(prefs: FormattingPreferences): String =
    if (prefs.showCadence) "Sentence highlight — ${prefs.cadenceWpm} wpm" else "Off"

@Composable
fun localizedCadenceSummary(prefs: FormattingPreferences): String =
    if (prefs.showCadence) {
        stringResource(R.string.ui_cadence_running_summary, prefs.cadenceWpm)
    } else {
        stringResource(R.string.ui_off)
    }

fun autoScheduleSummary(schedule: ThemeSchedule): String {
    fun t(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)
    return "Day ${t(schedule.dayStart)} · ${schedule.dayTheme.label()} → " +
        "Night ${t(schedule.nightStart)} · ${schedule.nightTheme.label()}"
}

fun autoThemeSummary(
    schedule: ThemeSchedule,
    autoMode: AutoReaderThemeMode,
    appThemeReaderThemes: AppThemeReaderThemes = AppThemeReaderThemes(),
): String = when (autoMode) {
    AutoReaderThemeMode.Schedule -> autoScheduleSummary(schedule)
    AutoReaderThemeMode.AppTheme -> "Light app · ${appThemeReaderThemes.lightTheme.label()} → " +
        "Dark app · ${appThemeReaderThemes.darkTheme.label()}"
}

@Composable
fun localizedAutoScheduleSummary(schedule: ThemeSchedule): String {
    fun t(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)
    return stringResource(
        R.string.ui_auto_schedule_summary,
        t(schedule.dayStart),
        schedule.dayTheme.localizedLabel(),
        t(schedule.nightStart),
        schedule.nightTheme.localizedLabel(),
    )
}

@Composable
fun localizedAutoThemeSummary(
    schedule: ThemeSchedule,
    autoMode: AutoReaderThemeMode,
    appThemeReaderThemes: AppThemeReaderThemes = AppThemeReaderThemes(),
): String = when (autoMode) {
    AutoReaderThemeMode.Schedule -> localizedAutoScheduleSummary(schedule)
    AutoReaderThemeMode.AppTheme -> stringResource(
        R.string.ui_auto_app_theme_summary,
        appThemeReaderThemes.lightTheme.localizedLabel(),
        appThemeReaderThemes.darkTheme.localizedLabel(),
    )
}
