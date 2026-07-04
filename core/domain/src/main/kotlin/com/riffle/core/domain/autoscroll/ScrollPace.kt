package com.riffle.core.domain.autoscroll

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily

data class LayoutContext(
    val wordsPerLine: Float,
    val lineHeightPx: Float,
)

fun pxPerSecond(speed: AutoScrollSpeed, layout: LayoutContext): Float {
    if (layout.wordsPerLine <= 0f || layout.lineHeightPx <= 0f) return 0f
    return (speed.wpm / 60f) * (layout.lineHeightPx / layout.wordsPerLine)
}

// Base body font size in CSS px (Readium's default 1rem).
private const val BASE_FONT_CSS_PX: Float = 16f

// Approximate average word length including the following space — matches typical English prose
// (~4.7-char word + 1 space).
private const val AVG_WORD_LEN_CHARS: Float = 5.5f

// Real prose does not fill lines to the margin — paragraph endings, dialog, chapter breaks, and
// hyphenation avoidance leave the average line noticeably shorter than the maximum. Without this
// correction the derived wordsPerLine is too high and auto-scroll pace comes out too slow.
private const val LINE_FILL_FACTOR: Float = 0.75f

// Readium's --RS__pageGutter default. Side padding per side ≈ pageGutter * --USER__pageMargins.
private const val PAGE_GUTTER_CSS_PX: Float = 8f

// Average glyph advance per em by family. Serif/sans condensed-ish; mono is roughly em-square at 0.6;
// the dyslexic face is wider than typical sans. Numbers are approximations — good enough to scale
// the auto-scroll pace, not to lay out text.
private fun avgEmAdvance(family: ReaderFontFamily): Float = when (family) {
    ReaderFontFamily.Original, ReaderFontFamily.Serif, ReaderFontFamily.Literata, ReaderFontFamily.Merriweather -> 0.50f
    ReaderFontFamily.SansSerif -> 0.52f
    ReaderFontFamily.Monospace -> 0.60f
    ReaderFontFamily.OpenDyslexic -> 0.58f
}

// Derive a LayoutContext from the live formatting preferences and viewport. The returned
// wordsPerLine reflects font size, font family, and margins; lineHeightPx reflects font size,
// line spacing, and device density. Both feed `pxPerSecond` so scroll pace tracks what the
// reader can actually see on screen.
fun layoutContextFor(
    prefs: FormattingPreferences,
    viewportWidthPx: Int,
    deviceDensity: Float,
): LayoutContext {
    val fontSizeCssPx = BASE_FONT_CSS_PX * prefs.fontSize
    val lineHeightDevicePx = fontSizeCssPx * prefs.lineSpacing * deviceDensity
    val viewportCssPx = if (deviceDensity > 0f) viewportWidthPx / deviceDensity else 0f
    val gutterCssPx = PAGE_GUTTER_CSS_PX * prefs.margins
    val innerCssPx = (viewportCssPx - 2f * gutterCssPx).coerceAtLeast(0f)
    val avgWordWidthCssPx = fontSizeCssPx * avgEmAdvance(prefs.fontFamily) * AVG_WORD_LEN_CHARS
    val wordsPerLine = if (avgWordWidthCssPx > 0f)
        (innerCssPx / avgWordWidthCssPx) * LINE_FILL_FACTOR else 0f
    return LayoutContext(wordsPerLine = wordsPerLine, lineHeightPx = lineHeightDevicePx)
}
