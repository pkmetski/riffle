package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard: mutations that change a highlight's rendered representation in the synthesised
 * Highlights-mode HTML (note add/remove, recolour, and delete) MUST reload the reader in Highlights
 * mode. The runtime decoration path doesn't touch the synthesised `<p>`/`<aside>` bodies, so
 * without a reload the on-screen view drifts from the underlying annotation store.
 *
 * The three mutating VM methods each need to include:
 *   if (source == ReaderSource.Highlights) { … openBook() … }
 *
 * ...within their `viewModelScope.launch { … }` body. This test flunks if any of the three loses
 * that pattern — cheaper than standing up an Android-heavy VM harness just to observe the reload
 * side-effect via a fake session, and covers the exact regression a future refactor could reintroduce
 * (either by moving the reload into a helper called from the wrong place or by dropping it).
 */
class HighlightsReloadOnMutationGuardTest {

    @Test
    fun `updateHighlightNote reloads openBook in Highlights mode`() {
        assertReloadsInHighlightsMode(functionName = "updateHighlightNote")
    }

    @Test
    fun `recolorHighlight reloads openBook in Highlights mode`() {
        assertReloadsInHighlightsMode(functionName = "recolorHighlight")
    }

    @Test
    fun `deleteHighlight reloads openBook in Highlights mode`() {
        assertReloadsInHighlightsMode(functionName = "deleteHighlight")
    }

    @Test
    fun `deleteAnnotation reloads openBook in Highlights mode`() {
        assertReloadsInHighlightsMode(functionName = "deleteAnnotation")
    }

    private fun assertReloadsInHighlightsMode(functionName: String) {
        val source = resolveVmSource().readText()
        val funBody = extractFunctionBody(source, functionName)
            ?: error("Could not locate `fun $functionName` body in EpubReaderViewModel.kt — did it move?")
        // The guard is intentionally loose: any `openBook()` call inside a
        // `if (source == ReaderSource.Highlights) { … }` branch counts. Delete flows fold in
        // reloadOrCloseHighlightsAfterDelete() plus the openBook call; note/recolor flows call
        // openBook directly. Both patterns satisfy this assertion.
        val hasHighlightsBranch = funBody.contains("source == ReaderSource.Highlights")
        // Either an explicit openBook() call (delete flows) OR the reloadHighlightsView() helper
        // (note/recolor flows). The helper internally calls openBook() plus the frame-yield the
        // note/recolor flows need to force Compose to observe the Loading state.
        val callsReload = funBody.contains("openBook()") || funBody.contains("reloadHighlightsView()")
        assertTrue(
            "$functionName must include an `if (source == ReaderSource.Highlights)` branch that " +
                "reloads via openBook() or reloadHighlightsView() — otherwise the synthesised HTML " +
                "shows stale note/colour state until the reader is reopened. Function body:\n$funBody",
            hasHighlightsBranch && callsReload,
        )
    }

    /** Locate the whole body of a top-level `fun <name>(…) { … }` in the VM source. */
    private fun extractFunctionBody(source: String, name: String): String? {
        val declRegex = Regex("""(?m)^\s{4}fun\s+$name\b""")
        val declMatch = declRegex.find(source) ?: return null
        // Find the first `{` after the declaration and walk to its matching `}`.
        var i = source.indexOf('{', startIndex = declMatch.range.first)
        if (i < 0) return null
        var depth = 0
        val start = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun resolveVmSource(): File {
        val rel = "src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt"
        val candidates = listOf(File(rel), File("app/$rel"))
        return candidates.firstOrNull { it.exists() } ?: error(
            "EpubReaderViewModel.kt not found. Tried: ${candidates.map { it.absolutePath }}",
        )
    }
}
