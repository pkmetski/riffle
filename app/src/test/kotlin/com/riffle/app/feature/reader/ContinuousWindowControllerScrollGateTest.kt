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

    private fun resolveSource(name: String): File {
        val relative = "src/main/kotlin/com/riffle/app/feature/reader/$name"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() } ?: error(
            "$name not found. Tried: ${candidates.map { it.absolutePath }}",
        )
    }
}
