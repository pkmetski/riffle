package com.riffle.shared.reader

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosDecodeWithRetryTest {

    @Test
    fun returnsResultOnFirstSuccessfulAttempt() = runTest {
        var calls = 0
        val result = iosDecodeWithRetry(attempts = 3, retryDelayMs = 0) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun retriesOnNullAndReturnsFirstNonNull() = runTest {
        var calls = 0
        val result = iosDecodeWithRetry(attempts = 3, retryDelayMs = 0) {
            calls++
            if (calls < 2) null else "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, calls)
    }

    @Test
    fun returnsNullAfterAllAttemptsExhausted() = runTest {
        var calls = 0
        val result = iosDecodeWithRetry<String>(attempts = 3, retryDelayMs = 0) {
            calls++
            null
        }
        assertNull(result)
        assertEquals(3, calls)
    }

    @Test
    fun singleAttemptDoesNotRetry() = runTest {
        var calls = 0
        val result = iosDecodeWithRetry<String>(attempts = 1, retryDelayMs = 0) {
            calls++
            null
        }
        assertNull(result)
        assertEquals(1, calls)
    }
}
