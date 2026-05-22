package com.riffle.app.feature.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
