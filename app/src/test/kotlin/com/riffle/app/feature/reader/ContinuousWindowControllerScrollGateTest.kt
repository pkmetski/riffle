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
    fun `openWindowAt arms smoothTailRevealSuppressed based on focusAnnotationId`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        val fnIdx = source.indexOf("private fun openWindowAt(")
        assertTrue("openWindowAt not found in ContinuousWindowController", fnIdx >= 0)
        val fnBody = source.substring(fnIdx, source.indexOf("\n    }", fnIdx) + 1)

        // The flag must be set from focusAnnotationId, not hardcoded to false.
        // When focusAnnotationId != null: flag = true → revealSmooth cannot fire the stale
        // anchor scroll even if the JS element query returns null (pre-migration TYPE_IMAGE with
        // no imageHref/fragment).
        // When focusAnnotationId == null: flag = false → revealSmooth runs normally.
        assertTrue(
            "openWindowAt must set smoothTailRevealSuppressed = focusAnnotationId != null so " +
            "revealSmooth cannot override the annotation landing even when the element is not " +
            "found immediately (pre-migration TYPE_IMAGE with null imageHref and no CFI fragment)",
            fnBody.contains("smoothTailRevealSuppressed = focusAnnotationId != null"),
        )
    }

    /**
     * Regression guard for the second part of the cross-chapter annotation focus bug: even after
     * [smoothTailRevealSuppressed] prevents the DOM-ready anchor fallback from overriding the
     * figure, a concurrent [serverLocatorEvents] whose `land()` closure was posted before any user
     * touch can still jump the reader back to the stale server position.
     *
     * Root cause: annotation panel taps arrive via Compose bottom sheet and never trigger
     * [onTouchDown] on [ContinuousReaderView], so [inWindowNavSupersededByTouch] can be false even
     * after a deliberate navigation. The in-window [land] closure and the cross-window branch must
     * both set the flag so a concurrent server [land] that runs after sees it and bails.
     */
    @Test
    fun `in-window land closure sets inWindowNavSupersededByTouch before scrollToLoadedChapter`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        // Locate the land lambda inside the in-window branch of navigateTo.
        // The lambda contains the guard `if (!inWindowNavSupersededByTouch)`.
        val landGuardIdx = source.indexOf("if (!inWindowNavSupersededByTouch) {")
        assertTrue("in-window land guard not found in ContinuousWindowController", landGuardIdx >= 0)
        // Extract the body of the lambda — from the guard's opening { to the next balanced }.
        val guardBodyStart = source.indexOf("{", landGuardIdx)
        val guardBodyEnd = source.indexOf("}", guardBodyStart)
        val guardBody = source.substring(guardBodyStart, guardBodyEnd + 1)

        assertTrue(
            "The in-window land() closure must set inWindowNavSupersededByTouch = true inside the " +
            "guard so a server-resume land() posted concurrently (before any user touch) sees the " +
            "flag and bails — annotation panel taps never trigger onTouchDown on the reader view",
            guardBody.contains("inWindowNavSupersededByTouch = true"),
        )
        // The flag must be set BEFORE scrollToLoadedChapter to avoid the race where
        // the server land() runs between the set and the actual scroll.
        val setIdx = guardBody.indexOf("inWindowNavSupersededByTouch = true")
        val scrollIdx = guardBody.indexOf("scrollToLoadedChapter(")
        assertTrue(
            "inWindowNavSupersededByTouch = true must precede scrollToLoadedChapter in the land() guard",
            setIdx in 0 until scrollIdx,
        )
    }

    @Test
    fun `cross-window navigateTo branch sets inWindowNavSupersededByTouch before openWindowAt`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        // The fix places "inWindowNavSupersededByTouch = true" immediately before the
        // openWindowAt( call in navigateTo's cross-window else-branch. Verify that the exact
        // sequence "inWindowNavSupersededByTouch = true\n…openWindowAt(" exists in the file —
        // this pattern is unique: the in-window branch sets it inside land() (not adjacent to an
        // openWindowAt call) and onTouchDown sets it in a completely different context.
        val flagBeforeOpen = Regex(
            """inWindowNavSupersededByTouch\s*=\s*true\s*\n\s*openWindowAt\(""",
            RegexOption.MULTILINE,
        )
        assertTrue(
            "Cross-window navigateTo branch must set inWindowNavSupersededByTouch = true " +
            "immediately before openWindowAt so a serverLocatorEvents that fires after the window " +
            "rebuild cannot tear down and reload the window to the stale server position",
            flagBeforeOpen.containsMatchIn(source),
        )
    }

    /**
     * Regression guard for the non-target, non-top chapter late-image drift bug:
     * when a chapter BETWEEN the top of the window and the target chapter grows after its initial
     * measurement (e.g. ch08 images load while the reader is at an annotation in ch09), the
     * annotated figure drifts downward while scroll stays put, causing the reader to show content
     * above the figure. The fix adds a scrollBy(delta) compensation path for this case.
     */
    @Test
    fun `non-top non-target non-placeholder chapter growth above viewport triggers scrollBy compensation`() {
        val source = resolveSource("ContinuousWindowController.kt").readText()
        // The compensation block must check: not wasPlaceholder, i != 0, delta > 0,
        // not the target href, and the slot bottom is at or above current scroll.
        assertTrue(
            "onHeightMeasured must compensate scroll when a non-top, non-target, non-placeholder " +
            "chapter grows and is entirely above the viewport — without this, the focused figure " +
            "drifts downward while scroll stays put (two-step annotation nav repro)",
            source.contains("!wasPlaceholder && i != 0 && delta != 0") &&
            source.contains("wv.chapterHref != pendingTargetHref && slotBottomBefore <= port.currentScrollY"),
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
