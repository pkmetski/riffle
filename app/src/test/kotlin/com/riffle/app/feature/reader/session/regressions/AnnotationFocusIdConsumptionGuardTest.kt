package com.riffle.app.feature.reader.session.regressions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level regression guard for the premature `pendingFocusAnnotationId` consumption bug.
 *
 * ## The bug (attempt-2 annotation-focus flake)
 *
 * `ChapterWebView.loadChapter()` sets `chapterHref` immediately (before `onPageFinished`) so
 * the decoration controller's `forEachLoadedWebView` can see a chapter while it is still
 * loading. When `applyAnnotationHighlights` fires shortly after `openWindowAt` it finds the
 * still-loading chapter by href and evaluates the marks JS. The JS callback fires and calls
 * `onAnnotationHighlightsApplied`.
 *
 * Before the fix, `onAnnotationHighlightsApplied` consumed `pendingFocusAnnotationId`
 * (set it to null) IMMEDIATELY — before the async `annotationOffsetTopDevicePx` callback
 * returned. When the callback arrived, the annotation was not yet in the DOM (chapter still
 * loading) so `annOffset == null` and no scroll happened. With `pendingFocusAnnotationId`
 * already null, the REAL `onAnnotationHighlightsApplied` (fired from `onChapterLoaded` once
 * the page actually finished) saw no pending id, fell into the fallback
 * `reapplyLandingAfterFallback?.invoke()`, and scrolled to the progression-based anchor
 * position instead of the annotation — the test's observed wrong-scroll failure.
 *
 * ## The fix
 *
 * Move the `pendingFocusAnnotationId = null` assignment from `onAnnotationHighlightsApplied`
 * into `scrollToFocusAnnotation`'s `annotationOffsetTopDevicePx` callback, guarded by
 * `annOffset != null`. The id is only consumed once the annotation is confirmed to be
 * positioned in the loaded DOM. A premature call (chapter still loading → annOffset null)
 * leaves the id intact for the real `onChapterLoaded`-triggered retry.
 *
 * This is a source-level guard: the async callback chain cannot be exercised in an isolated
 * JVM test without wiring up a real WebView. The guard fails on a literal revert of the fix.
 */
class AnnotationFocusIdConsumptionGuardTest {

    private val controllerSource: String by lazy {
        val candidates = listOf(
            "app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousWindowController.kt",
            "src/main/kotlin/com/riffle/app/feature/reader/ContinuousWindowController.kt",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull("ContinuousWindowController.kt must be readable from the test cwd", file)
        file!!.readText()
    }

    /**
     * Extract the body of the named private function by locating its declaration, finding the
     * opening brace, and matching to the closing brace (brace-balanced). Returns the balanced
     * body string (opening brace through closing brace inclusive).
     */
    private fun extractFunctionBody(source: String, funDecl: String): String {
        val lines = source.lines()
        val startLine = lines.indexOfFirst { it.contains(funDecl) }
        assertTrue("'$funDecl' not found in ContinuousWindowController", startLine >= 0)
        val flat = lines.drop(startLine).joinToString("\n")
        val braceStart = flat.indexOf('{')
        assertTrue("Opening brace after '$funDecl' not found", braceStart >= 0)
        var depth = 0
        var i = braceStart
        val sb = StringBuilder()
        while (i < flat.length) {
            val c = flat[i]
            sb.append(c)
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun `onAnnotationHighlightsApplied does NOT directly set pendingFocusAnnotationId to null`() {
        val body = extractFunctionBody(controllerSource, "fun onAnnotationHighlightsApplied")
        assertFalse(
            "pendingFocusAnnotationId = null must NOT appear directly inside onAnnotationHighlightsApplied. " +
                "Before the fix this assignment was synchronous — it ran before annotationOffsetTopDevicePx " +
                "returned, so a premature call (chapter still loading) permanently consumed the id and the " +
                "real onChapterLoaded-triggered retry had no id to work with. The assignment must live " +
                "inside scrollToFocusAnnotation's annotationOffsetTopDevicePx callback where it is guarded " +
                "by annOffset != null.",
            body.contains("pendingFocusAnnotationId = null"),
        )
    }

    @Test
    fun `scrollToFocusAnnotation sets pendingFocusAnnotationId to null inside annotationOffsetTopDevicePx callback`() {
        val body = extractFunctionBody(controllerSource, "fun scrollToFocusAnnotation")
        // The callback lambda is the block after `annotationOffsetTopDevicePx(id) {`. Within it,
        // `pendingFocusAnnotationId = null` must appear AFTER the `annOffset == null` early-return
        // guard so that it is only reached when the annotation is found in the DOM.
        assertTrue(
            "scrollToFocusAnnotation must set pendingFocusAnnotationId = null inside the " +
                "annotationOffsetTopDevicePx callback (only when annOffset != null). " +
                "Without this guard, consuming the id before the annotation is in the DOM " +
                "means the real onChapterLoaded retry has no id and falls back to the anchor scroll.",
            body.contains("pendingFocusAnnotationId = null"),
        )
        // The null-check guard must come before the consumption so a null annOffset returns early
        // and never reaches the assignment.
        val nullCheckIdx = body.indexOf("if (annOffset == null) return")
        val consumptionIdx = body.indexOf("pendingFocusAnnotationId = null")
        assertTrue(
            "The 'if (annOffset == null) return' early-exit must appear BEFORE " +
                "'pendingFocusAnnotationId = null' in scrollToFocusAnnotation so a not-yet-loaded " +
                "chapter (annOffset null) skips consumption entirely.",
            nullCheckIdx in 0 until consumptionIdx,
        )
    }
}
