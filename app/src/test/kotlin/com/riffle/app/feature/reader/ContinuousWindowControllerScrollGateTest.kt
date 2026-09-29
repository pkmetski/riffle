package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for #1109: `landOnAnnotationOffset` must schedule its `scrollTo` call via
 * [ContinuousScrollPort.postAfterLayout] (not the bare [ContinuousScrollPort.post]).
 *
 * Using bare `post` fires the scroll before the NestedScrollView has processed the new chapter
 * height set by `applyChapterHeight` in the same `onHeightMeasured` callback. The NestedScrollView
 * then clamps `scrollTo(8395)` against its stale `maxScrollY = 3840` (derived from the old
 * 5760 px slot height, not the post-reflow height), landing the reader ~3 100 px short of the
 * annotated phrase — the exact signature in the CI failure report for
 * `AnnotationFocusHarnessTest.continuousMode_annotationTap_focusesAnnotationOnScreen`.
 *
 * `postAfterLayout` is backed by [androidx.core.view.doOnNextLayout], which fires after the
 * NestedScrollView's layout pass has updated its child heights and therefore its [maxScrollY].
 */
class ContinuousWindowControllerScrollGateTest {

    @Test
    fun `landOnAnnotationOffset uses postAfterLayout so scrollTo fires after the layout pass`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        val fnIdx = source.indexOf("fun landOnAnnotationOffset(")
        assertTrue("landOnAnnotationOffset not found in ContinuousWindowController", fnIdx >= 0)
        // The function body runs from its signature to the next closing-brace at the same indent.
        val fnBody = source.substring(fnIdx, source.indexOf("\n    }", fnIdx) + 1)

        assertTrue(
            "landOnAnnotationOffset must schedule scrollTo via port.postAfterLayout so the " +
            "NestedScrollView layout processes the new slot height before the scroll is clamped (#1109)",
            fnBody.contains("postAfterLayout"),
        )
        assertFalse(
            "landOnAnnotationOffset must NOT use bare port.post — that runs before layout and " +
            "clips scrollTo against stale maxScrollY (#1109)",
            fnBody.contains("port.post {"),
        )
    }

    /**
     * Regression guard for the cross-chapter annotation focus bug: when [ContinuousWindowController]
     * navigates cross-chapter to a TYPE_IMAGE annotation with smoothTail=true, [pendingInitialScroll]
     * fires at DOM-ready (before [onPageFinished]), cannot find the annotation yet, and falls back to
     * the CFI-anchor landing. This schedules [revealSmooth] → smoothScrollTo(section_top). Later,
     * [onAnnotationHighlightsApplied] fires, finds the annotation, and [landOnAnnotationOffset]
     * scrolls to the correct figure position. Without the fix, [revealSmooth] then fires and animates
     * AWAY to section_top, leaving the reader at the wrong position.
     *
     * Fix: [scrollToFocusAnnotation] sets smoothTailRevealSuppressed=true so [revealSmooth] only
     * lifts the nav cover without calling smoothScrollTo.
     */
    @Test
    fun `scrollToFocusAnnotation sets smoothTailRevealSuppressed before landOnAnnotationOffset`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        val fnIdx = source.indexOf("fun scrollToFocusAnnotation(")
        assertTrue("scrollToFocusAnnotation not found in ContinuousWindowController", fnIdx >= 0)
        val fnBody = source.substring(fnIdx, source.indexOf("\n    }", fnIdx) + 1)

        assertTrue(
            "scrollToFocusAnnotation must set smoothTailRevealSuppressed = true when the annotation " +
            "offset is found so the pending revealSmooth closure from the DOM-ready fallback landing " +
            "does not call smoothScrollTo(section_top) and override the correct figure position",
            fnBody.contains("smoothTailRevealSuppressed = true"),
        )
        // The flag must be set BEFORE landOnAnnotationOffset so the reveal closure can't fire
        // (onCurrentContentPainted is asynchronous and may already be scheduled) between the two.
        val flagIdx = fnBody.indexOf("smoothTailRevealSuppressed = true")
        val landIdx = fnBody.indexOf("landOnAnnotationOffset(")
        assertTrue(
            "smoothTailRevealSuppressed must be set before landOnAnnotationOffset is called",
            flagIdx in 0 until landIdx,
        )
    }

    @Test
    fun `revealSmooth checks smoothTailRevealSuppressed before calling smoothScrollTo`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        // Find the revealSmooth local fun inside postLandAt (inside pendingInitialScroll closure).
        val revealSmoothIdx = source.indexOf("fun revealSmooth()")
        assertTrue("revealSmooth not found in ContinuousWindowController", revealSmoothIdx >= 0)
        // Extract the body of revealSmooth — from the opening { to the closing }.
        val bodyStart = source.indexOf("{", revealSmoothIdx)
        val bodyEnd = source.indexOf("}", bodyStart)
        val revealBody = source.substring(bodyStart, bodyEnd + 1)

        assertTrue(
            "revealSmooth must check smoothTailRevealSuppressed before calling smoothScrollTo so " +
            "a stale anchor-fallback landing (from DOM-ready before decorations applied) cannot " +
            "override the pixel-accurate annotation offset from scrollToFocusAnnotation",
            revealBody.contains("smoothTailRevealSuppressed"),
        )
        // smoothScrollTo must be inside the if-not-suppressed branch, meaning
        // the check must appear BEFORE the smoothScrollTo call in the source.
        val checkIdx = revealBody.indexOf("smoothTailRevealSuppressed")
        val scrollIdx = revealBody.indexOf("smoothScrollTo")
        assertTrue(
            "smoothTailRevealSuppressed check must precede smoothScrollTo in revealSmooth",
            checkIdx < scrollIdx,
        )
    }

    @Test
    fun `openWindowAt resets smoothTailRevealSuppressed`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        val fnIdx = source.indexOf("private fun openWindowAt(")
        assertTrue("openWindowAt not found in ContinuousWindowController", fnIdx >= 0)
        val fnBody = source.substring(fnIdx, source.indexOf("\n    }", fnIdx) + 1)

        assertTrue(
            "openWindowAt must reset smoothTailRevealSuppressed = false so a fresh navigation " +
            "after a previous annotation focus does not suppress the new smooth tail",
            fnBody.contains("smoothTailRevealSuppressed = false"),
        )
    }

    private fun resolveSource(name: String): File {
        val relative = "src/main/kotlin/com/riffle/app/feature/reader/$name"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() } ?: error(
            "$name not found. Tried: ${candidates.map { it.absolutePath }}",
        )
    }
}
