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

    /**
     * The font scale as a whole percentage — "115%".
     *
     * Rounds, never truncates: Android's slider caption is `"%.0f%%"`, which rounds half-up, so a
     * `toInt()` here renders a different number for the same preference. #1066 fixed that in
     * [formattingSummary] but the iOS stepper one panel down kept its own `toInt()`, so a 1.149
     * scale read 115% in the summary row and 114% in the stepper directly beneath it. Every
     * surface that prints a font percentage calls this.
     */
    fun fontSizePercentLabel(scale: Float): String = "${(scale * 100).roundToInt()}%"

    /**
     * A scale factor as Android's typography captions print it — `"%.1f×"`, rounded half-up.
     * `String.format` is JVM-only, so build the two halves by hand.
     */
    fun scaleTimesLabel(scale: Float): String {
        val tenths = (scale * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10}×"
    }

    /** "Normal · 1.5×" — the line-spacing caption both platforms' formatting panels show. */
    fun lineSpacingCaption(scale: Float): String = "${lineSpacingWord(scale)} · ${scaleTimesLabel(scale)}"

    /**
     * "Normal · 1.0×" — the margins caption. iOS used to render margins as a percentage
     * (`"100%"`) while Android rendered a word plus a multiplier for the same preference.
     */
    fun marginsCaption(scale: Float): String = "${marginsWord(scale)} · ${scaleTimesLabel(scale)}"

    fun formattingSummary(prefs: FormattingPreferences): String =
        "${fontFamilyLabel(prefs.fontFamily)} · ${fontSizePercentLabel(prefs.fontSize)} · " +
            "${marginsWord(prefs.margins)} margins"

    fun orientationWord(orientation: ReaderOrientation): String = when (orientation) {
        ReaderOrientation.Horizontal -> "Paginated"
        ReaderOrientation.Vertical -> "Scroll"
        ReaderOrientation.Continuous -> "Continuous"
    }

    /**
     * `"<theme> · <mode> · map on|off"`, the subtitle of the Display drill-in row.
     *
     * [includeChapterMap] drops the trailing `map on|off` segment. The chapter map is a reader
     * overlay that only Android draws, so its toggle is absent from the iOS Display panel (#1072)
     * — and a summary that advertises a control the panel behind it does not offer is the same
     * "inert control" defect in the other direction. iOS passes false; Android keeps the default.
     */
    fun displaySummary(prefs: FormattingPreferences, includeChapterMap: Boolean = true): String {
        val mode = orientationWord(prefs.orientation)
        val theme = if (prefs.theme == ReaderTheme.Auto) {
            "Auto ${autoModeLabel(prefs.autoReaderThemeMode)}"
        } else {
            themeLabel(prefs.theme)
        }
        if (!includeChapterMap) return "$theme · $mode"
        val map = if (prefs.showChapterMap) "map on" else "map off"
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
