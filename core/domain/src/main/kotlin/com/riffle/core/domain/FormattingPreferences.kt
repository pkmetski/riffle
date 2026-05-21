package com.riffle.core.domain

data class FormattingPreferences(
    val fontSize: Float = 1.0f,
    val theme: ReaderTheme = ReaderTheme.Light,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.Serif,
    val lineSpacing: Float = 1.2f,
    val margins: Float = 1.0f,
    val orientation: ReaderOrientation = ReaderOrientation.Paginated,
)

enum class ReaderTheme { Light, Dark, Sepia }
enum class ReaderFontFamily { Serif, SansSerif, Monospace, Literata, Merriweather, OpenDyslexic }
enum class ReaderOrientation { Paginated, Scroll }
