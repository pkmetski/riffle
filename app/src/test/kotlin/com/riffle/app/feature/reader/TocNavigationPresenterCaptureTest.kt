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

    /** Extracts the source text inside LaunchedEffect(onNavigationEvents) { … }. */
    private fun onNavigationEventsEffectBody(): String {
        val marker = "LaunchedEffect(onNavigationEvents)"
        val start = screenSource.indexOf(marker)
        assertTrue("$marker not found in EpubReaderScreen", start >= 0)
        val braceStart = screenSource.indexOf('{', start)
        assertTrue("opening brace after $marker not found", braceStart >= 0)
        var depth = 0
        var i = braceStart
        while (i < screenSource.length) {
            when (screenSource[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return screenSource.substring(braceStart, i + 1)
                }
            }
            i++
        }
        error("$marker brace block never closes — EpubReaderScreen may be malformed")
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
