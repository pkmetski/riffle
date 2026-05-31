package com.riffle.core.domain

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
    val doublePageSpread: Boolean = DEFAULT_DOUBLE_PAGE_SPREAD,
    val justifyText: Boolean = DEFAULT_JUSTIFY_TEXT,
) {
    companion object {
        const val DEFAULT_FONT_SIZE: Float = 1.0f
        const val DEFAULT_LINE_SPACING: Float = 1.2f
        const val DEFAULT_MARGINS: Float = 1.0f
        const val DEFAULT_SHOW_CHAPTER_MAP: Boolean = true
        const val DEFAULT_SHOW_READING_PROGRESS_LABELS: Boolean = false
        const val DEFAULT_SHOW_CURRENT_CHAPTER_LABEL: Boolean = false
        const val DEFAULT_DOUBLE_PAGE_SPREAD: Boolean = false
        const val DEFAULT_JUSTIFY_TEXT: Boolean = false
        val DEFAULT_THEME: ReaderTheme = ReaderTheme.Light
        val DEFAULT_FONT_FAMILY: ReaderFontFamily = ReaderFontFamily.Serif
        val DEFAULT_ORIENTATION: ReaderOrientation = ReaderOrientation.Horizontal
    }
}

enum class ReaderTheme { Light, Dark, DarkDim, Sepia }
enum class ReaderFontFamily { Serif, SansSerif, Monospace, Literata, Merriweather, OpenDyslexic }
enum class ReaderOrientation { Horizontal, Vertical }
