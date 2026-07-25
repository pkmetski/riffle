package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
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
    fun `touchmove and touchend cancel the pending long-press via the shared cancelFigureLongPress helper`() {
        assertTrue(script.contains("addEventListener('touchmove'"))
        assertTrue(script.contains("addEventListener('touchend'"))
        // Both handlers delegate to cancelFigureLongPress() so the timer clear, target clear, and
        // dynamic touchcancel removal all happen through a single code path.
        val moveIdx = script.indexOf("addEventListener('touchmove'")
        val moveEnd = script.indexOf("}, true);", moveIdx).let { it + "}, true);".length }
        val moveBlock = script.substring(moveIdx, moveEnd)
        assertTrue("touchmove must call cancelFigureLongPress()", moveBlock.contains("cancelFigureLongPress()"))

        val endIdx = script.indexOf("addEventListener('touchend'")
        val endEnd = script.indexOf("}, true);", endIdx).let { it + "}, true);".length }
        val endBlock = script.substring(endIdx, endEnd)
        assertTrue("touchend must call cancelFigureLongPress()", endBlock.contains("cancelFigureLongPress()"))
    }

    /**
     * Scrolling must take precedence over long-press: when the parent scroll container claims the
     * touch stream the WebView receives ACTION_CANCEL (JS: touchcancel). The [cancelFigureLongPress]
     * helper is the single point that clears the timer, the target, and removes the listener.
     * This assertion flips red if the helper is removed or the clearTimeout is dropped.
     */
    @Test
    fun `cancelFigureLongPress function clears timer and target so scroll cancels the annotations menu`() {
        val fnIdx = script.indexOf("function cancelFigureLongPress()")
        assertTrue("cancelFigureLongPress helper function must be declared", fnIdx >= 0)
        // Locate the function body. trimIndent() on installScript's raw string strips 0 spaces
        // (the interpolated JS constants have 0-indent lines which floor the minimum). The raw
        // 12-space indent of the function's closing `}` is therefore preserved verbatim in the
        // output, so "\n            }" (12 spaces) correctly identifies it. The function body has
        // no nested standalone-`}` on its own line (the one `if` is a single-line statement),
        // so the first 12-space `}` after the declaration IS the closing brace.
        val bodyStart = script.indexOf("{", fnIdx)
        val bodyEnd = script.indexOf("\n            }", bodyStart)
        val fnBody = script.substring(bodyStart, bodyEnd + 1)
        assertTrue("cancelFigureLongPress must clearTimeout(longPressTimer)", fnBody.contains("clearTimeout(longPressTimer)"))
        assertTrue("cancelFigureLongPress must null out longPressTarget", fnBody.contains("longPressTarget = null"))
        assertTrue("cancelFigureLongPress must remove the touchcancel listener", fnBody.contains("removeEventListener('touchcancel', cancelFigureLongPress, true)"))
    }

    /**
     * Regression test for the selection toolbar disappearing in paginated mode on newer WebView
     * builds (Chrome 120+): a permanent capture-phase touchcancel listener on document fires
     * during Chrome's gesture handoff from long-press recognition to text-selection mode and
     * suppresses the subsequent startActionMode call that shows the toolbar.
     *
     * The fix is to register the touchcancel guard ONLY inside the touchstart handler and only
     * when a figure was detected — text-selection long-presses return early before that point,
     * so no touchcancel listener exists during their gesture lifecycle.
     *
     * This assertion flips red if someone restores the permanent global listener.
     */
    @Test
    fun `touchcancel listener is registered dynamically inside touchstart not as a permanent global listener`() {
        // The registration must appear INSIDE the touchstart handler, not outside it.
        // Check: touchcancel registration comes AFTER the figure-detection early-return guard.
        val earlyReturnIdx = script.indexOf("if (!el) return;")
        assertTrue("early-return guard (if !el return) must exist in touchstart", earlyReturnIdx >= 0)
        val dynamicRegIdx = script.indexOf("document.addEventListener('touchcancel', cancelFigureLongPress, true)")
        assertTrue("touchcancel must be registered via the named cancelFigureLongPress reference", dynamicRegIdx >= 0)
        assertTrue(
            "touchcancel registration must come AFTER the figure-detection early-return, " +
                "so it is never registered during a text-selection long-press",
            dynamicRegIdx > earlyReturnIdx,
        )
        // There must be NO anonymous-function touchcancel listener — that was the PR #599 shape
        // that caused the regression and must never come back. Check both no-arg and e-arg forms.
        assertFalse(
            "permanent anonymous touchcancel listener must not exist (PR #601 regression guard)",
            script.contains("addEventListener('touchcancel', function()") ||
                script.contains("addEventListener('touchcancel', function("),
        )
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
