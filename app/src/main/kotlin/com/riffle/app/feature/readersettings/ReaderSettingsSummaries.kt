package com.riffle.app.feature.readersettings

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
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.feature.settings.ReaderSettingsSummaries
import com.riffle.feature.settings.label
import kotlin.math.roundToInt

// The un-localised summaries below are thin forwarders: the derivations themselves live in
// feature:settings/commonMain (ReaderSettingsSummaries) so the iOS settings surface produces the
// same strings. The `localized*` variants stay here because they need a Compose composition to
// read string resources.
//
// `ReaderTheme.label()` is NOT redeclared here — it is the shared extension in
// feature:settings/ReaderThemeLabel.kt, imported above, which is the single source both
// platforms use.

fun AutoReaderThemeMode.label(): String = ReaderSettingsSummaries.autoModeLabel(this)

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

fun ReaderFontFamily.label(): String = ReaderSettingsSummaries.fontFamilyLabel(this)

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

fun lineSpacingWord(value: Float): String = ReaderSettingsSummaries.lineSpacingWord(value)

@Composable
fun lineSpacingLabel(value: Float): String = when {
    value < 1.15f -> stringResource(R.string.ui_tight)
    value < 1.35f -> stringResource(R.string.ui_compact)
    value < 1.55f -> stringResource(R.string.ui_normal)
    value < 1.75f -> stringResource(R.string.ui_comfortable)
    value < 1.95f -> stringResource(R.string.ui_roomy)
    else -> stringResource(R.string.ui_spacious)
}

fun marginsWord(value: Float): String = ReaderSettingsSummaries.marginsWord(value)

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
    ReaderSettingsSummaries.formattingSummary(prefs)

@Composable
fun localizedFormattingSummary(prefs: FormattingPreferences): String =
    stringResource(
        R.string.ui_formatting_summary,
        prefs.fontFamily.localizedLabel(),
        (prefs.fontSize * 100).roundToInt(),
        stringResource(R.string.ui_margins_summary, marginsLabel(prefs.margins)),
    )

fun displaySummary(prefs: FormattingPreferences): String =
    ReaderSettingsSummaries.displaySummary(prefs)

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
    ReaderSettingsSummaries.behaviorSummary(keepScreenOn, volumeKeyNavigationEnabled)

fun autoScrollSummary(prefs: FormattingPreferences): String =
    ReaderSettingsSummaries.autoScrollSummary(prefs)

@Composable
fun localizedAutoScrollSummary(prefs: FormattingPreferences): String =
    if (prefs.showAutoScroll) {
        stringResource(R.string.ui_auto_scroll_running_summary, prefs.autoScrollWpm)
    } else {
        stringResource(R.string.ui_off)
    }

fun cadenceSummary(prefs: FormattingPreferences): String =
    ReaderSettingsSummaries.cadenceSummary(prefs)

@Composable
fun localizedCadenceSummary(prefs: FormattingPreferences): String =
    if (prefs.showCadence) {
        stringResource(R.string.ui_cadence_running_summary, prefs.cadenceWpm)
    } else {
        stringResource(R.string.ui_off)
    }

fun autoScheduleSummary(schedule: ThemeSchedule): String =
    ReaderSettingsSummaries.autoScheduleSummary(schedule)

fun autoThemeSummary(
    schedule: ThemeSchedule,
    autoMode: AutoReaderThemeMode,
    appThemeReaderThemes: AppThemeReaderThemes = AppThemeReaderThemes(),
): String = ReaderSettingsSummaries.autoThemeSummary(schedule, autoMode, appThemeReaderThemes)

@Composable
fun localizedAutoScheduleSummary(schedule: ThemeSchedule): String {
    fun t(time: LocalMinuteTime) = ReaderSettingsSummaries.clockTime(time)
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
