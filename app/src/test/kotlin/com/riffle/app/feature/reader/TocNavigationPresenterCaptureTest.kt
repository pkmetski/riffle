package com.riffle.app.feature.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the continuous→paginated TOC navigation bug.
 *
 * Root cause: [LaunchedEffect(onNavigationEvents)] in EpubReaderScreen is NOT keyed on
 * [readiumPresenter]. When a book opens in Continuous mode [readiumPresenter] is null; the
 * coroutine captures that null at launch time. After the user switches to Paginated mode
 * [readiumPresenter] becomes non-null, but the running coroutine still holds the null reference —
 * so [readiumPresenter?.navigateToLink] in the paginated branch silently no-ops and every TOC tap
 * is dropped until the book is closed and reopened.
 *
 * The fix wraps [readiumPresenter] with [rememberUpdatedState] (`currentReadiumPresenter`). Reading
 * the state inside the coroutine always returns the current presenter, even without restarting the
 * LaunchedEffect.
 *
 * This is a source-level guard: Compose LaunchedEffect body capture is a runtime property and
 * there is no isolated pure decision to unit-test. The guard fails on a literal revert of the fix.
 */
class TocNavigationPresenterCaptureTest {

    private val screenSource: String by lazy {
        val candidates = listOf(
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
            "src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull("EpubReaderScreen.kt must be readable from the test cwd", file)
        file!!.readText()
    }

    /**
     * Returns the source lines between `LaunchedEffect(onNavigationEvents)` (inclusive) and the
     * next top-level `LaunchedEffect(` call (exclusive).
     *
     * Line-range extraction avoids the brace-counter's weakness with `${ }` string templates: a
     * template expression opens a nested `{` that the counter would count, potentially closing the
     * balance early and returning a truncated body that no longer contains the navigation call.
     * Since the navigation LaunchedEffects are separated by blank lines / comments, slicing to the
     * next `LaunchedEffect(` gives a reliable body without parsing Kotlin syntax.
     */
    private fun onNavigationEventsEffectBody(): String {
        val lines = screenSource.lines()
        val startIdx = lines.indexOfFirst { it.contains("LaunchedEffect(onNavigationEvents)") }
        assertTrue(
            "LaunchedEffect(onNavigationEvents) not found in EpubReaderScreen",
            startIdx >= 0,
        )
        // Find the next LaunchedEffect( call after this one — that's where the body ends.
        val endIdx = lines.drop(startIdx + 1).indexOfFirst {
            it.trimStart().startsWith("LaunchedEffect(")
        }.takeIf { it >= 0 }?.let { startIdx + 1 + it } ?: lines.size
        return lines.subList(startIdx, endIdx).joinToString("\n")
    }

    @Test
    fun `currentReadiumPresenter is declared with rememberUpdatedState`() {
        assertTrue(
            "currentReadiumPresenter must be declared via rememberUpdatedState(readiumPresenter). " +
                "Without this, LaunchedEffect(onNavigationEvents) captures a null presenter when the " +
                "book opens in Continuous mode and drops TOC navigation after switching to Paginated.",
            screenSource.contains("val currentReadiumPresenter by rememberUpdatedState(readiumPresenter)"),
        )
    }

    @Test
    fun `onNavigationEvents effect calls navigateToLink via the rememberUpdatedState wrapper`() {
        val body = onNavigationEventsEffectBody()
        assertTrue(
            "navigateToLink inside LaunchedEffect(onNavigationEvents) must be called on " +
                "currentReadiumPresenter (the rememberUpdatedState wrapper) not on readiumPresenter " +
                "directly. A direct call captures null when the book opens in Continuous mode and " +
                "silently drops TOC navigation after a continuous→paginated mode switch.",
            body.contains("currentReadiumPresenter?.navigateToLink"),
        )
    }

    @Test
    fun `onNavigationEvents effect does not call navigateToLink directly on the raw presenter`() {
        val body = onNavigationEventsEffectBody()
        assertFalse(
            "readiumPresenter?.navigateToLink must not appear inside LaunchedEffect(onNavigationEvents). " +
                "Use currentReadiumPresenter?.navigateToLink instead — the rememberUpdatedState wrapper " +
                "ensures the coroutine always sees the latest presenter after a mode switch.",
            body.contains("readiumPresenter?.navigateToLink"),
        )
    }
}
