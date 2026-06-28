package com.riffle.core.domain.autoscroll

data class LayoutContext(
    val wordsPerLine: Float,
    val lineHeightPx: Float,
)

fun pxPerSecond(speed: AutoScrollSpeed, layout: LayoutContext): Float {
    if (layout.wordsPerLine <= 0f || layout.lineHeightPx <= 0f) return 0f
    return (speed.wpm / 60f) * (layout.lineHeightPx / layout.wordsPerLine)
}
