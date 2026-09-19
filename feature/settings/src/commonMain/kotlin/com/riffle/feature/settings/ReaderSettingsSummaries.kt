package com.riffle.feature.settings

import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import kotlin.math.roundToInt

/**
 * The un-localised reader-settings row summaries — the one-line previews each settings drill-in
 * shows under its title ("Serif · 110% · Normal margins").
 *
 * These are pure string derivations over `core:domain` preference types with no Compose or Android
 * dependency, so they live here and are called by both platforms' settings surfaces. Android's
 * `com.riffle.app.feature.readersettings` top-level functions are thin forwarders; the localised
 * `stringResource`-backed variants stay Android-side because they need a Compose composition.
 */
object ReaderSettingsSummaries {

    /** Delegates to [ReaderTheme.label] so the mapping exists exactly once in this module. */
    fun themeLabel(theme: ReaderTheme): String = theme.label()

    fun autoModeLabel(mode: AutoReaderThemeMode): String = when (mode) {
        AutoReaderThemeMode.Schedule -> "Time based"
        AutoReaderThemeMode.AppTheme -> "App theme"
    }

    fun fontFamilyLabel(font: ReaderFontFamily): String = when (font) {
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
        "${fontFamilyLabel(prefs.fontFamily)} · ${(prefs.fontSize * 100).roundToInt()}% · " +
            "${marginsWord(prefs.margins)} margins"

    fun orientationWord(orientation: ReaderOrientation): String = when (orientation) {
        ReaderOrientation.Horizontal -> "Paginated"
        ReaderOrientation.Vertical -> "Scroll"
        ReaderOrientation.Continuous -> "Continuous"
    }

    fun displaySummary(prefs: FormattingPreferences): String {
        val mode = orientationWord(prefs.orientation)
        val map = if (prefs.showChapterMap) "map on" else "map off"
        val theme = if (prefs.theme == ReaderTheme.Auto) {
            "Auto ${autoModeLabel(prefs.autoReaderThemeMode)}"
        } else {
            themeLabel(prefs.theme)
        }
        return "$theme · $mode · $map"
    }

    fun behaviorSummary(keepScreenOn: Boolean, volumeKeyNavigationEnabled: Boolean): String =
        "Keep screen ${if (keepScreenOn) "on" else "off"} · " +
            "volume nav ${if (volumeKeyNavigationEnabled) "on" else "off"}"

    fun autoScrollSummary(prefs: FormattingPreferences): String =
        if (prefs.showAutoScroll) "Hands-free scroll — ${prefs.autoScrollWpm} wpm" else "Off"

    fun cadenceSummary(prefs: FormattingPreferences): String =
        if (prefs.showCadence) "Sentence highlight — ${prefs.cadenceWpm} wpm" else "Off"

    /** `HH:MM`, zero-padded. `String.format` is JVM-only, so pad by hand. */
    fun clockTime(time: LocalMinuteTime): String =
        "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

    fun autoScheduleSummary(schedule: ThemeSchedule): String =
        "Day ${clockTime(schedule.dayStart)} · ${themeLabel(schedule.dayTheme)} → " +
            "Night ${clockTime(schedule.nightStart)} · ${themeLabel(schedule.nightTheme)}"

    fun autoThemeSummary(
        schedule: ThemeSchedule,
        autoMode: AutoReaderThemeMode,
        appThemeReaderThemes: AppThemeReaderThemes = AppThemeReaderThemes(),
    ): String = when (autoMode) {
        AutoReaderThemeMode.Schedule -> autoScheduleSummary(schedule)
        AutoReaderThemeMode.AppTheme ->
            "Light app · ${themeLabel(appThemeReaderThemes.lightTheme)} → " +
                "Dark app · ${themeLabel(appThemeReaderThemes.darkTheme)}"
    }
}
