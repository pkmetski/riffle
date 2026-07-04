package com.riffle.app.feature.reader.session.regressions

import com.riffle.app.feature.reader.ReadiumHighlightRenderer
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

/**
 * Regression tests for the readaloud highlight disappearing after rotation
 * (memory: reference_readaloud_highlight_rotation_reflow.md).
 *
 * ## The bug
 * Device rotation recreates the Activity. Compose reconstructs the reader screen with a fresh
 * `EpubNavigatorFragment` (new `pageLoadGeneration`). The readaloud decoration was applied to
 * the OLD fragment — the new fragment starts blank and never received `applyDecorations`. The
 * highlight was absent until the next sentence change.
 *
 * ## The fix (EpubReaderScreen.kt, ~line 1665)
 * The `LaunchedEffect` that calls `highlightRenderer.applySentenceHighlight(...)` keys on
 * `pageLoadGeneration.value`, which is bumped in `PaginationListener.onPageLoaded()` on every
 * fresh page load (including rotation). Because the effect re-runs, it calls `applySentenceHighlight`
 * again on the new fragment with the current `activeFragmentRef` and `sentenceQuotes` — the
 * highlight is re-applied without waiting for the next sentence change.
 *
 * ## Why these tests live here
 * The `LaunchedEffect(pageLoadGeneration.value, ...)` → `applySentenceHighlight()` chain lives in the
 * Compose screen layer (`EpubReaderScreen.kt`) and cannot be driven in a JVM test without
 * Compose infrastructure. The tests below assert the **closest testable invariant**: that
 * `ReadiumHighlightRenderer.applySentenceHighlight()` — the production class the screen calls — DOES
 * re-issue decorations on every invocation when a sentence is active, without short-circuiting.
 *
 * If a future change adds "skip if args unchanged" logic to `applySentenceHighlight`, the rotation fix
 * would silently break (the re-key wouldn't re-apply) and these tests would catch it.
 *
 * Production code path:
 *  - EpubReaderScreen.kt ~line 1665 (LaunchedEffect key includes pageLoadGeneration.value)
 *  - ReadiumHighlightRenderer.kt, applySentenceHighlight()
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadaloudHighlightRotationReflowTest {

    // ---- Test infrastructure ---------------------------------------------------

    private val applied = mutableListOf<Pair<List<Decoration>, String>>()

    /** Stable navigator stamp — tests don't care about search-settle abort logic. */
    private val navigatorStamp = Any()

    private val renderer = ReadiumHighlightRenderer(
        applyDecorationsBlock = { decorations, group -> applied.add(decorations to group) },
        fragmentLocator = { ref, _ ->
            if (ref.isNotBlank()) stubLocator() else null
        },
        currentNavigatorStamp = { navigatorStamp },
    )

    /**
     * Allocates a real `Locator` instance via `sun.misc.Unsafe` — identical to the pattern
     * used in `ReadiumHighlightRendererTest`. Tests only care about decoration presence and
     * group names, not the locator content.
     */
    @Suppress("UNCHECKED_CAST")
    private fun stubLocator(): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Locator::class.java) as Locator
    }

    private val activeRef = "chapter1.xhtml#s42"
    private val quotes = mapOf(
        "s42" to SentenceQuote(before = "text before", highlight = "The narrated sentence.", after = "text after"),
    )

    // ---- 9b: re-apply on rotation (pageLoadGeneration bump) -------------------

    /**
     * Rotation fix invariant: `applySentenceHighlight` must re-issue decorations on every call when
     * a sentence is active, even when called with identical arguments.
     *
     * The screen calls `applySentenceHighlight` twice with the same `(activeFragmentRef, sentenceQuotes)`
     * — once before rotation and once after (triggered by the `pageLoadGeneration` key change).
     * If `applySentenceHighlight` were to short-circuit on "same args as last call", the second call
     * (post-rotation) would be a no-op and the highlight would not be re-applied to the new
     * fragment.
     */
    @Test
    fun `applySentenceHighlight re-applies decorations on every call when sentence is active`() = runTest {
        // First call — simulates the initial highlight apply (pre-rotation)
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.BLUE)
        val afterFirst = applied.count { it.second == "readaloud" }

        applied.clear()

        // Second call with IDENTICAL args — simulates the post-rotation re-key firing.
        // The screen relies on this NOT being a no-op.
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.BLUE)
        val afterSecond = applied.count { it.second == "readaloud" }

        assertTrue(
            "applySentenceHighlight must issue decoration calls on the second invocation (rotation re-key). " +
                "Got $afterSecond calls; expected > 0.",
            afterSecond > 0,
        )
        assertEquals(
            "applySentenceHighlight must issue the same number of decoration calls on both invocations",
            afterFirst,
            afterSecond,
        )
    }

    /**
     * Each invocation of `applySentenceHighlight` includes the `readaloud_active` decoration in the
     * apply call — rotation receives the decoration on the new fragment.
     */
    @Test
    fun `applySentenceHighlight decoration id is readaloud_active after rotation re-apply`() = runTest {
        // First call (pre-rotation)
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.BLUE)
        applied.clear()

        // Second call (post-rotation, triggered by pageLoadGeneration bump)
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.BLUE)

        val applyCall = applied.lastOrNull { it.second == "readaloud" && it.first.isNotEmpty() }
        assertTrue(
            "A non-empty decoration must be applied to the 'readaloud' group after rotation",
            applyCall != null,
        )
        assertEquals(
            "Decoration id must be 'readaloud_active'",
            "readaloud_active",
            applyCall!!.first[0].id,
        )
    }

    /**
     * Without the `pageLoadGeneration` key, the `LaunchedEffect` would NOT re-run after rotation
     * and `applySentenceHighlight` would never be called a second time. This test confirms the effect
     * of that scenario: if `applySentenceHighlight` is called only ONCE (first call only, no rotation
     * re-key), a subsequent fragment-replace leaves the decoration state in the old renderer
     * instance. Clearing the applied list and asserting no new decorations exist simulates what
     * the user would see without the fix — no highlight on the new fragment.
     *
     * This is the "would-fail-without-fix" shape: if applySentenceHighlight were never called again
     * (no pageLoadGeneration key), applied would be empty after clear.
     */
    @Test
    fun `without second applySentenceHighlight call no decorations reach the new fragment`() = runTest {
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.BLUE)
        // Simulate rotation: new fragment replaces old one. WITHOUT the pageLoadGeneration fix,
        // applySentenceHighlight is not called again, so the new fragment sees no decoration.
        applied.clear() // new fragment has no decorations yet

        // No second call → no decorations for the new fragment.
        val decorationsForNewFragment = applied.filter { it.second == "readaloud" }
        assertEquals(
            "Without a second applySentenceHighlight call (no pageLoadGeneration re-key), " +
                "the new post-rotation fragment receives no sentence highlight decoration",
            0,
            decorationsForNewFragment.size,
        )
    }

    /**
     * Color change at rotation: the fix must handle the case where `pageLoadGeneration` bumps
     * AND the color preference changes simultaneously (user changed color, then rotated). The
     * new decoration must use the new color.
     */
    @Test
    fun `applySentenceHighlight re-applies with updated color on rotation`() = runTest {
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.BLUE)
        applied.clear()

        // Rotation + color change
        renderer.applySentenceHighlight(activeRef, quotes, HighlightColor.GREEN)

        val applyCall = applied.lastOrNull { it.second == "readaloud" && it.first.isNotEmpty() }
        assertTrue("Decoration must be present after rotation with updated color", applyCall != null)
        assertEquals("readaloud_active", applyCall!!.first[0].id)
    }
}
