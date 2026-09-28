package com.riffle.app.feature.reader

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the scroll/paginated→continuous TOC navigation bug.
 *
 * Root cause: [ContinuousReaderCoordinator] holds `viewFlow = MutableStateFlow(null)`. When the
 * user enters continuous mode for the first time `attach(view)` sets `viewFlow.value = view`.
 * When they switch AWAY from continuous mode (paginated/scroll) the [ContinuousReaderView]
 * [AndroidView] is removed from composition and the view is destroyed — but nothing reset
 * `viewFlow.value` to null. On the NEXT entry into continuous mode `onTocNavigation` calls
 * `viewFlow.filterNotNull().first()` which returns the stale destroyed view immediately (it is
 * non-null) and then calls `navigateTo` on a detached view — a silent no-op that makes every
 * TOC tap do nothing.
 *
 * The fix adds [ContinuousReaderCoordinator.detach] which resets `viewFlow.value = null`, and
 * wires it to the [AndroidView]'s `onRelease` callback so the view flow is cleared whenever
 * the [ContinuousReaderView] leaves composition.
 *
 * Source-level pins are the only assertions that flip red on a literal revert: the coordinator
 * detach clears `viewFlow`, and the screen wires `onRelease` to call it.
 */
class ContinuousViewDetachGuardTest {

    private fun readFile(vararg candidates: String): String {
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull("None of ${candidates.toList()} were readable from the test cwd", file)
        return file!!.readText()
    }

    private val coordinatorSource: String by lazy {
        readFile(
            "app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderCoordinator.kt",
            "src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderCoordinator.kt",
        )
    }

    private val screenSource: String by lazy {
        readFile(
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
            "src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
        )
    }

    @Test
    fun `ContinuousReaderCoordinator has a detach method that resets the viewFlow`() {
        assertTrue(
            "ContinuousReaderCoordinator must have a `fun detach()` method. Without it, the stale " +
                "destroyed ContinuousReaderView stays in viewFlow after a mode switch and silently " +
                "swallows TOC navigation on the next re-entry into continuous mode.",
            coordinatorSource.contains("fun detach()"),
        )
    }

    @Test
    fun `ContinuousReaderCoordinator detach resets viewFlow to null`() {
        val detachStart = coordinatorSource.indexOf("fun detach()")
        assertTrue("fun detach() not found in ContinuousReaderCoordinator", detachStart >= 0)
        val braceStart = coordinatorSource.indexOf('{', detachStart)
        assertTrue("opening brace after fun detach() not found", braceStart >= 0)
        var depth = 0
        var i = braceStart
        val sb = StringBuilder()
        while (i < coordinatorSource.length) {
            val c = coordinatorSource[i]
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
        val body = sb.toString()
        assertTrue(
            "ContinuousReaderCoordinator.detach() must set viewFlow.value = null so that " +
                "onTocNavigation suspends correctly on the next re-entry into continuous mode " +
                "instead of using the stale destroyed view. detach() body: `$body`",
            body.contains("viewFlow.value = null"),
        )
    }

    @Test
    fun `EpubReaderScreen calls coordinator detach inside the onRelease block of the ContinuousReaderView AndroidView`() {
        // Extract the onRelease = { … } block body and assert coordinator.detach() lives inside
        // it. A co-existence check (two separate contains) would pass even if coordinator.detach()
        // moved to a DisposableEffect, which looks like a natural alternative but would NOT clear
        // the viewFlow at the right moment (DisposableEffect keys on isContinuous; its onDispose
        // runs after recomposition, which may be after the next TOC navigation fires and picks up
        // the stale view from viewFlow).
        val marker = "onRelease = {"
        val start = screenSource.indexOf(marker)
        assertTrue(
            "onRelease = { not found in EpubReaderScreen — ContinuousReaderView AndroidView must " +
                "have an onRelease callback that calls coordinator.detach()",
            start >= 0,
        )
        val braceStart = start + marker.length - 1  // the '{' is the last char of the marker
        var depth = 0
        var i = braceStart
        val sb = StringBuilder()
        while (i < screenSource.length) {
            val c = screenSource[i]
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
        val onReleaseBody = sb.toString()
        assertTrue(
            "coordinator.detach() must appear inside the onRelease = { … } block body. " +
                "Moving it elsewhere (e.g. a DisposableEffect) leaves viewFlow non-null after " +
                "mode switch and silently causes the next TOC tap to navigate on the stale view. " +
                "onRelease body: `$onReleaseBody`",
            onReleaseBody.contains("coordinator.detach()"),
        )
    }
}
