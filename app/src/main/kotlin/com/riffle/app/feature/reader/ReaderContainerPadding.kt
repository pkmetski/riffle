package com.riffle.app.feature.reader

// Top/bottom padding (px) to reserve on the Readium fragment container before any WebView paints.
// Paginated reflowable only. In scroll mode the container fills the screen and the WebView sits
// permanently below topPx — content scrolls inside the WebView, not the container, so that gap
// never closes (permanent white strip). Fixed-layout pages are pre-sized; padding would letterbox
// them.
//
// Top is 0.8× the horizontal gutter (~16dp base), bottom 1.0× (~20dp), both scaling with the
// user's margins preference — matching the ratio of the old :root padding override that was
// replaced by this static approach to fix the "page falls from above" bug (#175).
internal fun readerContainerPaddingPx(
    margins: Float,
    density: Float,
    isFixedLayout: Boolean,
    isScrollMode: Boolean,
): Pair<Int, Int> {
    if (isFixedLayout || isScrollMode) return 0 to 0
    val top = (16f * margins * density + 0.5f).toInt()
    val bottom = (20f * margins * density + 0.5f).toInt()
    return top to bottom
}
