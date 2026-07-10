package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rhino (org.mozilla.javascript) is not on this repo's classpath, so these are string-shape
 * assertions against [FigureTapScript.installScript]'s emitted JS rather than evaluated-JS-behaviour
 * tests. They guard the long-press wiring and the `riffleFiguresInsideRange` entry point added on
 * top of the existing tap-detection logic. Real behavioural coverage for the tap path lives in the
 * instrumentation [FigureTapScriptTest] under `androidTest`; the long-press/range-scan behavioural
 * coverage lands in Task 13's instrumentation harness.
 */
class FigureTapScriptTest {

    private val script = FigureTapScript.installScript(FigureTapScript.PAGED_BRIDGE_NAME)

    @Test
    fun `script embeds the figure caption walker constants`() {
        assertTrue(script.contains(FigureCaptionWalker.CAPTION_RESOLVER_JS))
        assertTrue(script.contains(FigureCaptionWalker.SVG_SERIALIZER_JS))
        assertTrue(script.contains(FigureCaptionWalker.FIGURES_IN_RANGE_JS))
    }

    @Test
    fun `script extracts a shared detectFigureAt helper reused by tap and long-press`() {
        assertTrue(script.contains("function detectFigureAt(x, y)"))
        // Both paths must call the same detector rather than duplicating the walk.
        assertTrue(script.contains("detectFigureAt(t.clientX, t.clientY)"))
    }

    @Test
    fun `script attaches a touchstart listener with a 500ms long-press timer`() {
        assertTrue(script.contains("addEventListener('touchstart'"))
        assertTrue(script.contains("setTimeout(function() {"))
        assertTrue(script.contains("}, 500);"))
    }

    @Test
    fun `long-press callback invokes onFigureLongPress with a JSON payload`() {
        assertTrue(script.contains("onFigureLongPress(JSON.stringify(payload))"))
    }

    @Test
    fun `long-press payload includes kind caption href svg and elementId`() {
        listOf("kind:", "caption:", "href:", "svg:", "elementId:").forEach {
            assertTrue("missing payload field $it", script.contains(it))
        }
    }

    @Test
    fun `touchmove and touchend clear the pending long-press timer`() {
        assertTrue(script.contains("addEventListener('touchmove'"))
        assertTrue(script.contains("addEventListener('touchend'"))
        // Both handlers must clear the same timer variable to cancel a pending long-press.
        assertTrue(script.contains("clearTimeout(longPressTimer)"))
    }

    @Test
    fun `script exposes window riffleFiguresInsideRange as a callable entry point`() {
        assertTrue(script.contains("window.riffleFiguresInsideRange"))
    }

    @Test
    fun `installScript still wires onFigureTap for the given bridge name`() {
        // Regression: extending the script for long-press must not disturb the existing tap wiring.
        assertTrue(script.contains("window.RiffleFigureBridge.onFigureTap(JSON.stringify(p))"))
    }

    /**
     * Fix 2026-07-10: the capture-phase click handler must NOT swallow taps inside a synthesised
     * Highlights-view figure block (`<figure class="riffle-fig">`), otherwise the accent-bar tap
     * span's onclick can't fire and tapping the coloured bar opens the figure-zoom overlay
     * instead of the annotation editor. `findFigure` walks up looking for that class first and
     * returns null when it sees it, letting the tap propagate to the span's own onclick.
     */
    @Test
    fun `findFigure skips elided-view figure blocks so the accent-bar tap can fire`() {
        assertTrue(
            "findFigure must recognise the highlights-view figure class",
            script.contains("'riffle-fig'"),
        )
        assertTrue(
            "findFigure must bail out when it walks into a riffle-fig ancestor",
            script.contains("classList.contains('riffle-fig')"),
        )
    }
}
