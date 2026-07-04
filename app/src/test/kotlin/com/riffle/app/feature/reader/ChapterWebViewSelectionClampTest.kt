package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the bug where the floating selection toolbar landed off-screen below the
 * selection in continuous mode.
 *
 * Root cause (captured from a real device log on a Pixel-class edge-to-edge phone): the
 * ChapterWebView is laid out as tall as the full chapter, and the bottom of the WebView paints
 * INTO the gesture-bar / system-inset strip — pixels that are visually visible but lie outside the
 * window's visible display frame. Chromium reports a selection rect whose view-local y, once
 * translated by view.getLocationInWindow, places the rect ENTIRELY below the window viewport
 * bottom. The framework's FloatingActionMode then sees a content rect outside its viewport, the
 * above/below room calculation goes negative, and the toolbar slips off-screen.
 *
 * Real numbers from device (RIFFLE_SEL log):
 *   rect=(52, 1874-286, 1937)  locWin=(0,455)  viewWH=1080x1941  windowVisDisplay=(0,63-1080,2274)
 * Selection screen y = 455+1874=2329 .. 455+1937=2392 — both below viewport.bottom=2274.
 *
 * [clampSelectionYBandToWindow] snaps the rect's y-band into the window-visible portion so the
 * framework's geometry comparison happens against on-window coordinates.
 */
class ChapterWebViewSelectionClampTest {

    @Test
    fun `selection entirely below window viewport is clamped to viewport bottom`() {
        // Real captured numbers — selection at view-local y 1874..1937, WebView at window y 455,
        // viewport (0,63)..(_,2274), WebView 1941 tall.
        val (top, bottom) = clampSelectionYBandToWindow(
            rectTop = 1874,
            rectBottom = 1937,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 455,
            viewHeight = 1941,
        )
        // Visible band in view-local: max(0, 63-455)=0 .. min(1941, 2274-455)=1819.
        // Rect top 1874 > 1819 — clamped down to 1819. Same for bottom.
        assertEquals(1819, top)
        assertEquals(1819, bottom)
    }

    @Test
    fun `selection fully inside window viewport is untouched`() {
        // Selection near the middle of the visible band.
        val (top, bottom) = clampSelectionYBandToWindow(
            rectTop = 600,
            rectBottom = 660,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 455,
            viewHeight = 1941,
        )
        assertEquals(600, top)
        assertEquals(660, bottom)
    }

    @Test
    fun `selection partially above window viewport has its top pushed down to the band`() {
        // WebView scrolled so its top is above the window: locInWindow.y is negative.
        // Visible band in view-local: max(0, 63-(-400))=463 .. min(2000, 2274-(-400))=2000.
        val (top, bottom) = clampSelectionYBandToWindow(
            rectTop = 100,
            rectBottom = 500,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = -400,
            viewHeight = 2000,
        )
        assertEquals(463, top)
        assertEquals(500, bottom)
    }

    @Test
    fun `selection partially below window viewport has its bottom pulled up to the band`() {
        // Visible band in view-local: 0 .. 1819 (same as case 1). Rect straddles the bottom edge.
        val (top, bottom) = clampSelectionYBandToWindow(
            rectTop = 1800,
            rectBottom = 1900,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 455,
            viewHeight = 1941,
        )
        assertEquals(1800, top)
        assertEquals(1819, bottom)
    }

    @Test
    fun `view completely outside the viewport returns rect unchanged`() {
        // WebView's whole on-window range is above the viewport top. Visible band would be
        // [topLocal=0, bottomLocal=min(1000, 63-(-5000))=1000] — actually that's a no-op band.
        // To exercise the "view outside viewport" guard, place view BELOW the viewport entirely.
        // View at window y=3000, viewport (0,63)..(_,2274): topLocal=max(0,63-3000)=0,
        // bottomLocal=min(1000, 2274-3000)=min(1000,-726)=-726 -> bottomLocal<=topLocal -> no-op.
        val (top, bottom) = clampSelectionYBandToWindow(
            rectTop = 500,
            rectBottom = 600,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 3000,
            viewHeight = 1000,
        )
        assertEquals(500, top)
        assertEquals(600, bottom)
    }

    @Test
    fun `readerSelectionRectBottom clamp - selection fully inside band is unchanged`() {
        val (top, bottom) = clampReaderSelectionRectBottomYs(
            rectTop = 2300,
            rectBottom = 2340,
            viewportTop = 0,
            viewportBottom = 2400,
            viewLocationInWindowY = 0,
            viewHeight = 2400,
        )
        assertEquals(2300, top)
        assertEquals(2340, bottom)
    }

    @Test
    fun `readerSelectionRectBottom clamp - selection with bottom past band pulls only bottom`() {
        // Selection at last visible row; bottom sticks 90 px past the visible-band bottom into the
        // gesture-bar strip. Top stays where it is so framework's above-placement anchors to the
        // real line, not the visible-frame edge.
        val (top, bottom) = clampReaderSelectionRectBottomYs(
            rectTop = 2200,
            rectBottom = 2340,
            viewportTop = 0,
            viewportBottom = 2250,
            viewLocationInWindowY = 0,
            viewHeight = 2400,
        )
        assertEquals(2200, top)
        assertEquals(2250, bottom)
    }

    @Test
    fun `readerSelectionRectBottom clamp - selection fully below band pulls both to band bottom`() {
        // Real-device case: WebView paints into the gesture-bar area, whole selection below the
        // visible display frame. Both edges pulled so the framework picks above-placement.
        val (top, bottom) = clampReaderSelectionRectBottomYs(
            rectTop = 1874,
            rectBottom = 1937,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 455,
            viewHeight = 1941,
        )
        assertEquals(1819, top)
        assertEquals(1819, bottom)
    }

    @Test
    fun `readerSelectionRectBottom clamp - short view outside viewport returns unchanged`() {
        val (top, bottom) = clampReaderSelectionRectBottomYs(
            rectTop = 500,
            rectBottom = 600,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 3000,
            viewHeight = 1000,
        )
        assertEquals(500, top)
        assertEquals(600, bottom)
    }

    @Test
    fun `view shorter than the viewport caps bottomLocal at view height`() {
        // Short WebView (e.g. a tiny chapter), entirely inside the viewport. Visible band is the
        // whole view: [0, viewHeight]. Rect that already fits stays as-is.
        val (top, bottom) = clampSelectionYBandToWindow(
            rectTop = 50,
            rectBottom = 100,
            viewportTop = 63,
            viewportBottom = 2274,
            viewLocationInWindowY = 800,
            viewHeight = 300,
        )
        assertEquals(50, top)
        assertEquals(100, bottom)
    }
}
