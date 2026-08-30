package com.riffle.app.feature.reader.cbz

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A streaming-phase page decode is a network fetch under the hood. Before decodeWithRetry, a
 * single transient fetch failure (e.g. timeout while the background full-file download hogs the
 * link) permanently stuck the page: produceState ran the decode exactly once and a null bitmap
 * rendered as a blank page forever.
 */
class DecodeWithRetryTest {

    @Test fun `first success returns immediately without retrying`() = runTest {
        var calls = 0
        val result = decodeWithRetry(attempts = 3, retryDelayMs = 10) {
            calls++
            "page"
        }
        assertEquals("page", result)
        assertEquals(1, calls)
    }

    @Test fun `transient failure is retried until success`() = runTest {
        var calls = 0
        val result = decodeWithRetry(attempts = 3, retryDelayMs = 10) {
            calls++
            if (calls < 3) null else "page"
        }
        assertEquals("page", result)
        assertEquals(3, calls)
    }

    @Test fun `gives up after the attempt budget`() = runTest {
        var calls = 0
        val result = decodeWithRetry<String>(attempts = 3, retryDelayMs = 10) {
            calls++
            null
        }
        assertNull(result)
        assertEquals(3, calls)
    }
}
