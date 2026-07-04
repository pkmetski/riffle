package com.riffle.app.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: on the validated offline→online edge, [kickSweepsOnReconnect] must invoke both the
 * progress sweep and the annotation sweep, in that order (progress first so audiobook position
 * uploads land before the annotation write that a subsequent session may depend on). Edge
 * semantics themselves are pinned by
 * [com.riffle.core.domain.ConnectivityReconnectsTest]; this test pins the composition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSyncKickerTest {

    @Test
    fun `runs progress then annotation sweep on reconnect`() = runTest {
        val isOnline = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        val job = launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { calls += "progress" },
                runAnnotationSweep = { calls += "annotation" },
            )
        }
        runCurrent()

        isOnline.value = true
        runCurrent()

        assertEquals(listOf("progress", "annotation"), calls)
        job.cancel()
    }
}
