package com.riffle.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared offline→online edge semantics used by both the library auto-refresh listener
 * and the WebDAV sweep kicker (see [collectReconnects] KDoc). Every assertion here backs a live
 * behavior at a call site — if the edge detector drifts, one of the two consumers silently loses
 * its reconnect trigger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityReconnectsTest {

    @Test
    fun `fires on offline to online edge`() = runTest {
        val flow = MutableStateFlow(false)
        var calls = 0
        val job = launch { flow.collectReconnects { calls++ } }
        runCurrent()
        assertEquals(0, calls)

        flow.value = true
        runCurrent()
        assertEquals(1, calls)

        job.cancel()
    }

    @Test
    fun `does not fire when the initial state is already online`() = runTest {
        val flow = MutableStateFlow(true)
        var calls = 0
        val job = launch { flow.collectReconnects { calls++ } }
        runCurrent()

        assertEquals(0, calls)
        job.cancel()
    }

    @Test
    fun `re-fires on every subsequent offline to online edge`() = runTest {
        val flow = MutableStateFlow(false)
        var calls = 0
        val job = launch { flow.collectReconnects { calls++ } }
        runCurrent()

        flow.value = true
        runCurrent()
        flow.value = false
        runCurrent()
        flow.value = true
        runCurrent()

        assertEquals(2, calls)
        job.cancel()
    }

    @Test
    fun `does not re-fire on a redundant online emission`() = runTest {
        val flow = MutableStateFlow(false)
        var calls = 0
        val job = launch { flow.collectReconnects { calls++ } }
        runCurrent()

        flow.value = true
        runCurrent()
        flow.value = true // StateFlow dedupes same-value writes — the edge detector must not re-fire.
        runCurrent()

        assertEquals(1, calls)
        job.cancel()
    }
}
