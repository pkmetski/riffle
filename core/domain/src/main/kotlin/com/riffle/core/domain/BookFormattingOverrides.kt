package com.riffle.core.domain

data class BookFormattingOverrides(
    val fontSize: Float? = null,
    val theme: ReaderTheme? = null,
    val fontFamily: ReaderFontFamily? = null,
    val lineSpacing: Float? = null,
    val margins: Float? = null,
    val orientation: ReaderOrientation? = null,
    val showChapterMap: Boolean? = null,
    val showReadingProgressLabels: Boolean? = null,
    val showCurrentChapterLabel: Boolean? = null,
    val showReadingTimeEstimate: Boolean? = null,
    val doublePageSpread: Boolean? = null,
    val justifyText: Boolean? = null,
    val autoScrollWpm: Int? = null,
) {
    val isEmpty: Boolean
        get() = fontSize == null &&
            theme == null &&
            fontFamily == null &&
            lineSpacing == null &&
            margins == null &&
            orientation == null &&
            showChapterMap == null &&
            showReadingProgressLabels == null &&
            showCurrentChapterLabel == null &&
            showReadingTimeEstimate == null &&
            doublePageSpread == null &&
            justifyText == null &&
            autoScrollWpm == null

    fun applyTo(global: FormattingPreferences): FormattingPreferences = FormattingPreferences(
        fontSize = fontSize ?: global.fontSize,
        theme = theme ?: global.theme,
        fontFamily = fontFamily ?: global.fontFamily,
        lineSpacing = lineSpacing ?: global.lineSpacing,
        margins = margins ?: global.margins,
        orientation = orientation ?: global.orientation,
        showChapterMap = showChapterMap ?: global.showChapterMap,
        showReadingProgressLabels = showReadingProgressLabels ?: global.showReadingProgressLabels,
        showCurrentChapterLabel = showCurrentChapterLabel ?: global.showCurrentChapterLabel,
        showReadingTimeEstimate = showReadingTimeEstimate ?: global.showReadingTimeEstimate,
        doublePageSpread = doublePageSpread ?: global.doublePageSpread,
        justifyText = justifyText ?: global.justifyText,
        // themeSchedule is intentionally global-only (no per-book override). Threading
        // global through keeps the in-reader Auto resolution honouring the user's
        // configured day/night times instead of silently falling back to defaults.
        themeSchedule = global.themeSchedule,
        autoScrollWpm = autoScrollWpm ?: global.autoScrollWpm,
    )

    // Treat each field the user just changed (new != previously-effective) as an explicit book
    // override. Untouched fields keep their existing override state (often null = follow global).
    fun withChanges(
        previous: FormattingPreferences,
        new: FormattingPreferences,
    ): BookFormattingOverrides = copy(
        fontSize = if (new.fontSize != previous.fontSize) new.fontSize else fontSize,
        theme = if (new.theme != previous.theme) new.theme else theme,
        fontFamily = if (new.fontFamily != previous.fontFamily) new.fontFamily else fontFamily,
        lineSpacing = if (new.lineSpacing != previous.lineSpacing) new.lineSpacing else lineSpacing,
        margins = if (new.margins != previous.margins) new.margins else margins,
        orientation = if (new.orientation != previous.orientation) new.orientation else orientation,
        showChapterMap = if (new.showChapterMap != previous.showChapterMap) new.showChapterMap else showChapterMap,
        showReadingProgressLabels = if (new.showReadingProgressLabels != previous.showReadingProgressLabels) new.showReadingProgressLabels else showReadingProgressLabels,
        showCurrentChapterLabel = if (new.showCurrentChapterLabel != previous.showCurrentChapterLabel) new.showCurrentChapterLabel else showCurrentChapterLabel,
        showReadingTimeEstimate = if (new.showReadingTimeEstimate != previous.showReadingTimeEstimate) new.showReadingTimeEstimate else showReadingTimeEstimate,
        doublePageSpread = if (new.doublePageSpread != previous.doublePageSpread) new.doublePageSpread else doublePageSpread,
        justifyText = if (new.justifyText != previous.justifyText) new.justifyText else justifyText,
        autoScrollWpm = if (new.autoScrollWpm != previous.autoScrollWpm) new.autoScrollWpm else autoScrollWpm,
    )
}
