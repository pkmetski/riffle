package com.riffle.feature.settings.ui.readersettings
import androidx.compose.runtime.Composable
import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import com.riffle.feature.settings.ReaderSettingsSummaries
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

// Only the `localized*` variants live here: they need a Compose composition to read string
// resources, so they cannot move to commonMain. Everything they are built from — the theme, font,
// line-spacing and margin words, the summary compositions, the scale formatting — is the one
// implementation in feature:settings/commonMain (`ReaderSettingsSummaries`, `ReaderThemeLabel`),
// which iOS renders directly.
//
// This file used to also carry eleven un-localised `fun x(...) = ReaderSettingsSummaries.x(...)`
// forwarders. Every one of them was dead, and each one put a second top-level symbol with the
// shared name into the repo — the exact shape that invites someone to "just tweak" one copy.

@Composable
fun ReaderTheme.localizedLabel(): String = when (this) {
    // TODO: migrate to Res.string.ui_light
    ReaderTheme.Light -> stringResource(Res.string.ui_light)
    // TODO: migrate to Res.string.ui_dark
    ReaderTheme.Dark -> stringResource(Res.string.ui_dark)
    // TODO: migrate to Res.string.ui_dim
    ReaderTheme.DarkDim -> stringResource(Res.string.ui_dim)
    // TODO: migrate to Res.string.ui_sepia
    ReaderTheme.Sepia -> stringResource(Res.string.ui_sepia)
    // TODO: migrate to Res.string.ui_auto
    ReaderTheme.Auto -> stringResource(Res.string.ui_auto)
}

@Composable
fun AutoReaderThemeMode.localizedLabel(): String = when (this) {
    // TODO: migrate to Res.string.ui_time_based
    AutoReaderThemeMode.Schedule -> stringResource(Res.string.ui_time_based)
    // TODO: migrate to Res.string.ui_app_theme
    AutoReaderThemeMode.AppTheme -> stringResource(Res.string.ui_app_theme)
}

@Composable
fun ReaderFontFamily.localizedLabel(): String = when (this) {
    // TODO: migrate to Res.string.ui_original
    ReaderFontFamily.Original -> stringResource(Res.string.ui_original)
    // TODO: migrate to Res.string.ui_serif
    ReaderFontFamily.Serif -> stringResource(Res.string.ui_serif)
    // TODO: migrate to Res.string.ui_sans_serif
    ReaderFontFamily.SansSerif -> stringResource(Res.string.ui_sans_serif)
    // TODO: migrate to Res.string.ui_mono
    ReaderFontFamily.Monospace -> stringResource(Res.string.ui_mono)
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    // TODO: migrate to Res.string.ui_dyslexic
    ReaderFontFamily.OpenDyslexic -> stringResource(Res.string.ui_dyslexic)
}

@Composable
fun lineSpacingLabel(value: Float): String = when {
    // TODO: migrate to Res.string.ui_tight
    value < 1.15f -> stringResource(Res.string.ui_tight)
    // TODO: migrate to Res.string.ui_compact
    value < 1.35f -> stringResource(Res.string.ui_compact)
    // TODO: migrate to Res.string.ui_normal
    value < 1.55f -> stringResource(Res.string.ui_normal)
    // TODO: migrate to Res.string.ui_comfortable
    value < 1.75f -> stringResource(Res.string.ui_comfortable)
    // TODO: migrate to Res.string.ui_roomy
    value < 1.95f -> stringResource(Res.string.ui_roomy)
    // TODO: migrate to Res.string.ui_spacious
    else -> stringResource(Res.string.ui_spacious)
}

@Composable
fun marginsLabel(value: Float): String = when {
    // TODO: migrate to Res.string.ui_edge
    value < 0.5f -> stringResource(Res.string.ui_edge)
    // TODO: migrate to Res.string.ui_tight
    value < 0.85f -> stringResource(Res.string.ui_tight)
    // TODO: migrate to Res.string.ui_normal
    value < 1.25f -> stringResource(Res.string.ui_normal)
    // TODO: migrate to Res.string.ui_comfortable
    value < 1.75f -> stringResource(Res.string.ui_comfortable)
    // TODO: migrate to Res.string.ui_roomy
    value < 2.35f -> stringResource(Res.string.ui_roomy)
    // TODO: migrate to Res.string.ui_wide
    else -> stringResource(Res.string.ui_wide)
}

@Composable
fun localizedFormattingSummary(prefs: FormattingPreferences): String =
    stringResource(
        Res.string.ui_formatting_summary,
        prefs.fontFamily.localizedLabel(),
        (prefs.fontSize * 100).roundToInt(),
        // TODO: migrate to Res.string.ui_margins_summary
        stringResource(Res.string.ui_margins_summary, marginsLabel(prefs.margins)),
    )

@Composable
fun ReaderOrientation.localizedLabel(): String = when (this) {
    // TODO: migrate to Res.string.ui_paginated
    ReaderOrientation.Horizontal -> stringResource(Res.string.ui_paginated)
    // TODO: migrate to Res.string.ui_scroll
    ReaderOrientation.Vertical -> stringResource(Res.string.ui_scroll)
    // TODO: migrate to Res.string.ui_continuous
    ReaderOrientation.Continuous -> stringResource(Res.string.ui_continuous)
}

@Composable
fun localizedDisplaySummary(prefs: FormattingPreferences): String =
    stringResource(
        Res.string.ui_display_summary,
        if (prefs.theme == ReaderTheme.Auto) {
            // TODO: migrate to Res.string.ui_auto_theme_summary
            stringResource(Res.string.ui_auto_theme_summary, prefs.autoReaderThemeMode.localizedLabel())
        } else {
            prefs.theme.localizedLabel()
        },
        prefs.orientation.localizedLabel(),
        // TODO: migrate to Res.string.ui_map_on
        if (prefs.showChapterMap) stringResource(Res.string.ui_map_on) else stringResource(Res.string.ui_map_off),
    )

@Composable
fun localizedAutoScrollSummary(prefs: FormattingPreferences): String =
    if (prefs.showAutoScroll) {
        // TODO: migrate to Res.string.ui_auto_scroll_running_summary
        stringResource(Res.string.ui_auto_scroll_running_summary, prefs.autoScrollWpm)
    } else {
        // TODO: migrate to Res.string.ui_off
        stringResource(Res.string.ui_off)
    }

@Composable
fun localizedCadenceSummary(prefs: FormattingPreferences): String =
    if (prefs.showCadence) {
        // TODO: migrate to Res.string.ui_cadence_running_summary
        stringResource(Res.string.ui_cadence_running_summary, prefs.cadenceWpm)
    } else {
        // TODO: migrate to Res.string.ui_off
        stringResource(Res.string.ui_off)
    }

@Composable
fun localizedAutoScheduleSummary(schedule: ThemeSchedule): String {
    fun t(time: LocalMinuteTime) = ReaderSettingsSummaries.clockTime(time)
    return stringResource(
        Res.string.ui_auto_schedule_summary,
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
        Res.string.ui_auto_app_theme_summary,
        appThemeReaderThemes.lightTheme.localizedLabel(),
        appThemeReaderThemes.darkTheme.localizedLabel(),
    )
}
