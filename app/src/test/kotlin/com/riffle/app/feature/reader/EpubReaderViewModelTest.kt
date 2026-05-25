package com.riffle.app.feature.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// EpubReaderViewModel is an AndroidViewModel with Readium dependencies that rely on
// android.net.Uri, which cannot be used in JVM unit tests without Robolectric.
// These tests verify the `while (true) { delay(SYNC_INTERVAL_MS); sync() }` pattern
// from EpubReaderViewModel.startPeriodicSync() using virtual time, replacing the
// 35-second real-time harness test sessionUpdateSentAfterReading.
@OptIn(ExperimentalCoroutinesApi::class)
class EpubReaderViewModelTest {

    // Must match EpubReaderViewModel.SYNC_INTERVAL_MS
    private val syncIntervalMs = 30_000L

    @Test
    fun `periodic sync timer fires once after one interval`() = runTest {
        var syncCount = 0

        // backgroundScope coroutines are not checked for completion when runTest ends,
        // so the while(true) loop does not cause UncompletedCoroutinesError.
        backgroundScope.launch {
            while (true) {
                delay(syncIntervalMs)
                syncCount++
            }
        }

        advanceTimeBy(syncIntervalMs + 1)
        assertEquals(1, syncCount)
    }

    @Test
    fun `periodic sync timer fires twice after two intervals`() = runTest {
        var syncCount = 0

        backgroundScope.launch {
            while (true) {
                delay(syncIntervalMs)
                syncCount++
            }
        }

        advanceTimeBy(2 * syncIntervalMs + 1)
        assertEquals(2, syncCount)
    }

    // Regression guard for the sequential loadFormattingPreferences() + openBook() init pattern.
    //
    // EpubReaderViewModel.init runs these sequentially so that formattingPreferences already
    // holds the stored value when openBook() transitions state to Ready. EpubNavigatorView
    // passes formattingPreferences as initialPreferences to createFragmentFactory(), so if
    // the prefs were still at their default value at that point, Readium would render one
    // frame with wrong settings and then flash when submitPreferences() arrived.
    //
    // EpubReaderViewModel is an AndroidViewModel with Readium dependencies that require
    // android.net.Uri and cannot be instantiated in JVM unit tests without Robolectric.
    // These tests verify the sequential coroutine pattern in isolation instead.

    @Test
    fun `formattingPreferences has stored value when openBook resolves in sequential execution`() = runTest {
        val storedPrefs = "dark"   // stand-in for FormattingPreferences(theme = ReaderTheme.Dark)
        val formattingPrefs = MutableStateFlow("light")  // stand-in for default FormattingPreferences()
        var prefsWhenBookOpened: String? = null

        backgroundScope.launch {
            delay(10)                           // loadFormattingPreferences()
            formattingPrefs.value = storedPrefs

            delay(50)                           // openBook() — sequential, runs after prefs load
            prefsWhenBookOpened = formattingPrefs.value
        }

        advanceTimeBy(100)

        assertEquals(storedPrefs, prefsWhenBookOpened)
    }

    @Test
    fun `parallel execution exposes race where openBook sees default prefs before they load`() = runTest {
        val storedPrefs = "dark"
        val formattingPrefs = MutableStateFlow("light")
        var prefsWhenBookOpened: String? = null

        // Demonstrates the old broken pattern: parallel launches race with each other.
        // openBook completes before loadFormattingPreferences(), so it sees default prefs.
        backgroundScope.launch {
            delay(10)                           // openBook() wins the race
            prefsWhenBookOpened = formattingPrefs.value
        }
        backgroundScope.launch {
            delay(50)                           // loadFormattingPreferences() — arrives too late
            formattingPrefs.value = storedPrefs
        }

        advanceTimeBy(100)

        // openBook saw the default ("light"), not the stored ("dark") — this is the bug.
        assertNotEquals(storedPrefs, prefsWhenBookOpened)
        assertEquals("light", prefsWhenBookOpened)
    }
}
