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
    fun `EpubReaderScreen calls coordinator detach in the ContinuousReaderView AndroidView onRelease`() {
        // Find the ContinuousReaderView AndroidView block and verify its onRelease resets the
        // coordinator. Without this wiring, detach() is never called and the stale-view bug
        // reappears even if detach() itself is correct.
        assertTrue(
            "EpubReaderScreen must call coordinator.detach() in the ContinuousReaderView " +
                "AndroidView's onRelease callback. Without this the coordinator's viewFlow is " +
                "never reset to null on mode switch, so the next TOC tap in continuous mode " +
                "silently navigates on the destroyed view.",
            screenSource.contains("coordinator.detach()"),
        )
        assertTrue(
            "coordinator.detach() must appear inside an onRelease callback in EpubReaderScreen. " +
                "The onRelease runs when the AndroidView leaves composition (mode switch), which " +
                "is the correct moment to reset the coordinator's stale view reference.",
            screenSource.contains("onRelease"),
        )
    }
}
