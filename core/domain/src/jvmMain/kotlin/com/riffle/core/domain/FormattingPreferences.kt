package com.riffle.core.domain

import java.time.LocalTime
import com.riffle.core.models.HighlightColor

data class FormattingPreferences(
    val fontSize: Float = DEFAULT_FONT_SIZE,
    val theme: ReaderTheme = DEFAULT_THEME,
    val fontFamily: ReaderFontFamily = DEFAULT_FONT_FAMILY,
    val lineSpacing: Float = DEFAULT_LINE_SPACING,
    val margins: Float = DEFAULT_MARGINS,
    val orientation: ReaderOrientation = DEFAULT_ORIENTATION,
    val showChapterMap: Boolean = DEFAULT_SHOW_CHAPTER_MAP,
    val showReadingProgressLabels: Boolean = DEFAULT_SHOW_READING_PROGRESS_LABELS,
    val showCurrentChapterLabel: Boolean = DEFAULT_SHOW_CURRENT_CHAPTER_LABEL,
    val showReadingTimeEstimate: Boolean = DEFAULT_SHOW_READING_TIME_ESTIMATE,
    val doublePageSpread: Boolean = DEFAULT_DOUBLE_PAGE_SPREAD,
    val justifyText: Boolean = DEFAULT_JUSTIFY_TEXT,
    val themeSchedule: ThemeSchedule = ThemeSchedule(),
    val autoScrollWpm: Int = DEFAULT_AUTO_SCROLL_WPM,
    val showAutoScroll: Boolean = DEFAULT_SHOW_AUTO_SCROLL,
    val cadenceWpm: Int = DEFAULT_CADENCE_WPM,
    val showCadence: Boolean = DEFAULT_SHOW_CADENCE,
    val cadenceHighlightColor: HighlightColor = DEFAULT_CADENCE_HIGHLIGHT_COLOR,
    // Device capability, NOT a user preference. Flipped to false by the reader when the WebView's
    // `Intl.Segmenter` feature-detect fails (issue #403 gate). When false, Settings hides the
    // Cadence entry so users on unsupported WebViews (e.g. Android 7.1.1 with a stale system
    // WebView shipping Chrome ≤ 86) don't see an entry that never surfaces a runtime button.
    // Defaults to true — most Android WebViews are Chrome 87+ and the reader will flip it back
    // on the first tokenised chapter if the probe finds Intl.Segmenter present.
    val cadencePlatformSupported: Boolean = DEFAULT_CADENCE_PLATFORM_SUPPORTED,
) {
    companion object {
        const val DEFAULT_FONT_SIZE: Float = 1.0f
        const val DEFAULT_LINE_SPACING: Float = 1.2f
        const val DEFAULT_MARGINS: Float = 1.0f
        const val DEFAULT_SHOW_CHAPTER_MAP: Boolean = true
        const val DEFAULT_SHOW_READING_PROGRESS_LABELS: Boolean = false
        const val DEFAULT_SHOW_CURRENT_CHAPTER_LABEL: Boolean = false
        const val DEFAULT_SHOW_READING_TIME_ESTIMATE: Boolean = false
        const val DEFAULT_DOUBLE_PAGE_SPREAD: Boolean = false
        const val DEFAULT_JUSTIFY_TEXT: Boolean = false
        const val DEFAULT_AUTO_SCROLL_WPM: Int = 250
        const val DEFAULT_SHOW_AUTO_SCROLL: Boolean = false
        const val DEFAULT_CADENCE_WPM: Int = 250
        const val DEFAULT_SHOW_CADENCE: Boolean = false
        const val DEFAULT_CADENCE_PLATFORM_SUPPORTED: Boolean = true
        val DEFAULT_CADENCE_HIGHLIGHT_COLOR: HighlightColor = HighlightColor.YELLOW
        val DEFAULT_THEME: ReaderTheme = ReaderTheme.Light
        val DEFAULT_FONT_FAMILY: ReaderFontFamily = ReaderFontFamily.Original
        val DEFAULT_ORIENTATION: ReaderOrientation = ReaderOrientation.Horizontal
    }
}

enum class ReaderTheme { Light, Dark, DarkDim, Sepia, Auto }
enum class ReaderFontFamily { Original, Serif, SansSerif, Monospace, Literata, Merriweather, OpenDyslexic }
enum class ReaderOrientation { Horizontal, Vertical, Continuous }

data class ThemeSchedule(
    val dayStart: LocalTime = DEFAULT_DAY_START,
    val nightStart: LocalTime = DEFAULT_NIGHT_START,
    val dayTheme: ReaderTheme = DEFAULT_DAY_THEME,
    val nightTheme: ReaderTheme = DEFAULT_NIGHT_THEME,
) {
    fun resolve(now: LocalTime): ReaderTheme =
        if (isNight(now)) nightTheme else dayTheme

    // Treats the two times as boundaries on a 24h circle. The night arc runs
    // clockwise from nightStart up to (but not including) dayStart. At exactly
    // nightStart we are in night; at exactly dayStart we are in day. Equal
    // times collapse the night arc to length zero → always-day.
    private fun isNight(now: LocalTime): Boolean {
        if (dayStart == nightStart) return false
        return if (nightStart.isBefore(dayStart)) {
            !now.isBefore(nightStart) && now.isBefore(dayStart)
        } else {
            !now.isBefore(nightStart) || now.isBefore(dayStart)
        }
    }

    // The next clock-time at which `resolve` would return a different theme.
    // Used by the reader VM to schedule a one-shot delay until the next switch.
    // Returns dayStart when the schedule is degenerate so callers always have a value.
    fun nextBoundaryAfter(now: LocalTime): LocalTime {
        if (dayStart == nightStart) return dayStart
        return if (isNight(now)) dayStart else nightStart
    }

    companion object {
        val DEFAULT_DAY_START: LocalTime = LocalTime.of(7, 0)
        val DEFAULT_NIGHT_START: LocalTime = LocalTime.of(20, 0)
        val DEFAULT_DAY_THEME: ReaderTheme = ReaderTheme.Light
        val DEFAULT_NIGHT_THEME: ReaderTheme = ReaderTheme.Dark
    }
}

// Returns a copy with `theme` replaced by the schedule-resolved concrete theme when
// the user picked Auto. For any non-Auto theme this is a no-op identity. Reader VMs
// run this at render-time so every downstream consumer (Readium mapper, palette,
// chapter-rail backdrop) keeps reading `prefs.theme` and stays ignorant of Auto.
fun FormattingPreferences.withResolvedTheme(now: LocalTime): FormattingPreferences =
    if (theme == ReaderTheme.Auto) copy(theme = themeSchedule.resolve(now)) else this
