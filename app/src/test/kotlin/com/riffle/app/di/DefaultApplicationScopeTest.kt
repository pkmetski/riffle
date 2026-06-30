package com.riffle.app.di

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultApplicationScopeTest {

    @Test
    fun `launchSurvivable completes even if a per-screen scope is cancelled mid-write`() = runTest {
        val survivable = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val applicationScope = DefaultApplicationScope(survivable)

        var completed = false

        // A reader/player viewModelScope kicks off a terminal write through applicationScope and is
        // then torn down before the write finishes. The write must still complete.
        val viewModelScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        viewModelScope.launch {
            applicationScope.launchSurvivable {
                delay(100)
                completed = true
            }
        }
        advanceTimeBy(10)
        viewModelScope.cancel()

        advanceUntilIdle()
        assertTrue("the survivable launch must outlive caller teardown", completed)
    }

    @Test
    fun `withSurvivable runs the work to completion even when the caller cancels mid-await`() = runTest {
        val survivable = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val applicationScope = DefaultApplicationScope(survivable)

        var completed = false

        val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        callerScope.launch {
            applicationScope.withSurvivable {
                delay(100)
                completed = true
            }
        }
        advanceTimeBy(10)
        callerScope.cancel()

        advanceUntilIdle()
        assertTrue("the survivable work proceeds to completion when the caller cancels", completed)
    }

    @Test
    fun `withSurvivable returns the value to a caller that does not cancel`() = runTest {
        val survivable = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val applicationScope = DefaultApplicationScope(survivable)

        var result: Int? = null

        val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        callerScope.launch {
            result = applicationScope.withSurvivable {
                delay(50)
                42
            }
        }

        advanceUntilIdle()
        assertEquals(42, result)
    }
}
