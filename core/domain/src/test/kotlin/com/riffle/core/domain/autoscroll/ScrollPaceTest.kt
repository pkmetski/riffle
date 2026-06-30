package com.riffle.core.domain.autoscroll

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScrollPaceTest {

    private fun nearly(expected: Float, actual: Float, eps: Float = 0.05f) {
        assert(abs(expected - actual) < eps) { "expected ≈$expected, got $actual" }
    }

    private val typicalLayout = LayoutContext(wordsPerLine = 9f, lineHeightPx = 28f)

    @Test
    fun `default 250 wpm in typical layout is roughly 13 px per second`() {
        // (250 / 60) * (28 / 9) ≈ 12.96 px/s
        nearly(12.96f, pxPerSecond(AutoScrollSpeed.Default, typicalLayout))
    }

    @Test
    fun `doubling wpm doubles px per second`() {
        val a = pxPerSecond(AutoScrollSpeed.of(150), typicalLayout)
        val b = pxPerSecond(AutoScrollSpeed.of(300), typicalLayout)
        nearly(2f * a, b)
    }

    @Test
    fun `larger line height increases px per second proportionally`() {
        val a = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 28f))
        val b = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 56f))
        nearly(2f * a, b)
    }

    @Test
    fun `more words per line decreases px per second proportionally`() {
        val a = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 28f))
        val b = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(18f, 28f))
        nearly(a / 2f, b)
    }

    @Test
    fun `zero words per line yields zero pace`() {
        assertEquals(0f, pxPerSecond(AutoScrollSpeed.Default, LayoutContext(0f, 28f)), 0.0001f)
    }

    @Test
    fun `zero line height yields zero pace`() {
        assertEquals(0f, pxPerSecond(AutoScrollSpeed.Default, LayoutContext(9f, 0f)), 0.0001f)
    }

    // --- layoutContextFor -----------------------------------------------------

    private val phone = LayoutInputs(viewportWidthPx = 1233, deviceDensity = 3f)

    private data class LayoutInputs(val viewportWidthPx: Int, val deviceDensity: Float)

    private fun ctxFor(
        prefs: FormattingPreferences,
        inputs: LayoutInputs = phone,
    ): LayoutContext = layoutContextFor(prefs, inputs.viewportWidthPx, inputs.deviceDensity)

    @Test
    fun `doubling font size more than doubles px per second`() {
        // Bigger font => bigger lineHeight AND fewer wordsPerLine => roughly 4x px/s.
        val small = ctxFor(FormattingPreferences(fontSize = 1.0f))
        val large = ctxFor(FormattingPreferences(fontSize = 2.0f))
        val smallPace = pxPerSecond(AutoScrollSpeed.of(250), small)
        val largePace = pxPerSecond(AutoScrollSpeed.of(250), large)
        assertTrue(
            "expected large pace > 3x small pace, got small=$smallPace large=$largePace",
            largePace > 3f * smallPace,
        )
    }

    @Test
    fun `larger line spacing increases line height proportionally`() {
        val tight = ctxFor(FormattingPreferences(lineSpacing = 1.0f))
        val loose = ctxFor(FormattingPreferences(lineSpacing = 2.0f))
        nearly(2f * tight.lineHeightPx, loose.lineHeightPx, eps = 0.5f)
        // wordsPerLine independent of line spacing
        nearly(tight.wordsPerLine, loose.wordsPerLine, eps = 0.01f)
    }

    @Test
    fun `larger margins reduce words per line`() {
        val narrow = ctxFor(FormattingPreferences(margins = 1.0f))
        val wide = ctxFor(FormattingPreferences(margins = 3.0f))
        assertTrue(
            "wider margins should reduce wordsPerLine: narrow=${narrow.wordsPerLine} wide=${wide.wordsPerLine}",
            wide.wordsPerLine < narrow.wordsPerLine,
        )
    }

    @Test
    fun `monospace has fewer words per line than serif at same size`() {
        val serif = ctxFor(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        val mono = ctxFor(FormattingPreferences(fontFamily = ReaderFontFamily.Monospace))
        assertTrue(
            "monospace should fit fewer words per line: serif=${serif.wordsPerLine} mono=${mono.wordsPerLine}",
            mono.wordsPerLine < serif.wordsPerLine,
        )
    }

    @Test
    fun `device density scales line height in device pixels`() {
        val ldpi = ctxFor(FormattingPreferences(), LayoutInputs(411, 1f))
        val xxhdpi = ctxFor(FormattingPreferences(), LayoutInputs(1233, 3f))
        nearly(3f * ldpi.lineHeightPx, xxhdpi.lineHeightPx, eps = 0.5f)
    }

    @Test
    fun `zero viewport width yields zero words per line`() {
        val c = ctxFor(FormattingPreferences(), LayoutInputs(0, 3f))
        assertEquals(0f, c.wordsPerLine, 0.0001f)
    }
}
