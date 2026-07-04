package com.riffle.app.feature.reader

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `LaunchedEffect(returnNavEvents, …)` key list in `EpubReaderScreen`. The bug the fix
 * closes: the effect was keyed only on the Flow itself, which is the same instance across every
 * mode switch. On a paginated/vertical ↔ continuous flip the coroutine kept running with the
 * `readerPresenter` and `isContinuous` it had captured at first entry — so tapping Back after
 * switching modes drove the OLD (now invisible) navigator and the return card did nothing.
 *
 * Adding `readerPresenter` to the key list restarts the coroutine on every mode flip, letting it
 * re-capture the correct presenter and mode. This test asserts the key list contains it, because
 * removing it — either by intent or by a stale merge conflict resolution — silently reintroduces
 * the "Back popup does nothing after switching modes" regression.
 *
 * Compose LaunchedEffect keying is a runtime property; there is no isolated pure decision to test.
 * A source-level pin is the only assertion that flips red on a literal revert of the fix.
 */
class ReturnNavEffectKeysTest {

    private val screenSource: String by lazy {
        val candidates = listOf(
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
            "src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
        assertNotNull("EpubReaderScreen.kt must be readable from the test cwd", file)
        file!!.readText()
    }

    private fun assertKeysOnPresenter(effectMarker: String) {
        val start = screenSource.indexOf(effectMarker)
        assertTrue(
            "expected `$effectMarker` in EpubReaderScreen — did the effect move or get renamed?",
            start >= 0,
        )
        val header = screenSource.substring(start, start + 200)
        val keyList = header.substringAfter("LaunchedEffect(").substringBefore(")")
        assertTrue(
            "`$effectMarker` LaunchedEffect must key on readerPresenter to survive a mode flip; " +
                "current keys: `$keyList`",
            keyList.contains("readerPresenter"),
        )
    }

    @Test
    fun `returnNavEvents LaunchedEffect keys on readerPresenter so a mode flip restarts the collect`() {
        assertKeysOnPresenter("LaunchedEffect(returnNavEvents")
    }

    @Test
    fun `serverLocatorEvents LaunchedEffect keys on readerPresenter so a peer-position sync after a mode flip lands on the visible navigator`() {
        assertKeysOnPresenter("LaunchedEffect(serverLocatorEvents")
    }

    @Test
    fun `searchNavigationEvents LaunchedEffect keys on readerPresenter so tapping a search result after a mode flip lands on the visible navigator`() {
        assertKeysOnPresenter("LaunchedEffect(searchNavigationEvents")
    }
}
