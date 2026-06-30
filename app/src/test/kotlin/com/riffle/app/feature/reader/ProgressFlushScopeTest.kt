package com.riffle.app.feature.reader

import com.riffle.app.testing.TestApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressFlushScopeTest {

    // The fix: a close/pause progress write handed to the flush scope completes even when the screen's
    // own scope is torn down the instant after — the "press X / pause, then leave the book right away"
    // case where the in-flight ABS PATCH was being cancelled mid-network-write.
    @Test
    fun `a flush completes even when the caller's scope is cancelled immediately after submitting`() = runTest {
        val flusher = ProgressFlushScope(TestApplicationScope(CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())))
        var completed = false

        // Model the screen's viewModelScope: the user submits a close flush from it, then leaves the
        // book — tearing this scope down before the network write finishes.
        val viewModelScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        viewModelScope.launch {
            flusher.flush {
                delay(100) // an ABS PATCH in flight
                completed = true
            }
        }
        advanceTimeBy(10)
        viewModelScope.cancel() // leave the book right away

        advanceUntilIdle()
        assertTrue("the close flush must survive screen teardown", completed)
    }

    // Documents the bug being fixed: the identical write launched directly on the (cancellable) caller
    // scope is lost when the screen tears down first.
    @Test
    fun `the buggy pattern — a write launched on the caller scope is lost when it is cancelled`() = runTest {
        var completed = false
        val viewModelScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        viewModelScope.launch {
            delay(100)
            completed = true
        }
        advanceTimeBy(10)
        viewModelScope.cancel()

        advanceUntilIdle()
        assertFalse("a write on the cancelled scope is lost — this is the bug", completed)
    }
}
