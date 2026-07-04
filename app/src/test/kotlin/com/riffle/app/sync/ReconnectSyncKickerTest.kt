package com.riffle.app.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: the reconnect listener wired in [com.riffle.app.RiffleApplication.onCreate] must
 * invoke the sweeps directly on the offline→online edge of the validated `ConnectivityObserver`.
 * The previous shape called [AnnotationSyncScheduler.sweepNow], which enqueues a WorkManager job
 * gated on OS-level `NetworkType.CONNECTED` — on devices where the OS signal is unreliable (see
 * PR #402), that gate can keep the sweep queued indefinitely after the validated tracker has
 * already reported connectivity restored. Asserting the sweep callbacks run inline pins the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSyncKickerTest {

    @Test
    fun `runs both sweeps on offline to online edge`() = runTest {
        val isOnline = MutableStateFlow(false)
        var progress = 0
        var annotation = 0

        val job = launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { progress++ },
                runAnnotationSweep = { annotation++ },
            )
        }
        runCurrent()
        assertEquals(0, progress)
        assertEquals(0, annotation)

        isOnline.value = true
        runCurrent()
        assertEquals(1, progress)
        assertEquals(1, annotation)

        job.cancel()
    }

    @Test
    fun `does not fire when initial state is already online`() = runTest {
        val isOnline = MutableStateFlow(true)
        var progress = 0
        var annotation = 0

        val job = launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { progress++ },
                runAnnotationSweep = { annotation++ },
            )
        }
        runCurrent()

        assertEquals(0, progress)
        assertEquals(0, annotation)
        job.cancel()
    }

    @Test
    fun `re-fires on every subsequent offline to online edge`() = runTest {
        val isOnline = MutableStateFlow(false)
        var progress = 0
        var annotation = 0

        val job = launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { progress++ },
                runAnnotationSweep = { annotation++ },
            )
        }
        runCurrent()

        isOnline.value = true
        runCurrent()
        isOnline.value = false
        runCurrent()
        isOnline.value = true
        runCurrent()

        assertEquals(2, progress)
        assertEquals(2, annotation)
        job.cancel()
    }

    @Test
    fun `does not re-fire on redundant online emission`() = runTest {
        val isOnline = MutableStateFlow(false)
        var progress = 0
        var annotation = 0

        val job = launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { progress++ },
                runAnnotationSweep = { annotation++ },
            )
        }
        runCurrent()

        isOnline.value = true
        runCurrent()
        // StateFlow dedupes but assert the edge-detection is what enforces it, not the flow.
        isOnline.value = true
        runCurrent()

        assertEquals(1, progress)
        assertEquals(1, annotation)
        job.cancel()
    }
}
