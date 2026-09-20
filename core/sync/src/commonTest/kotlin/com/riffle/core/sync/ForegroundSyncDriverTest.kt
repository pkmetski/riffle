package com.riffle.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS has no WorkManager and no `BGTaskScheduler`, so [ForegroundSyncDriver] is the *only* thing
 * that ever retries a progress push that failed while offline (#1071 §14). These assertions pin
 * the four behaviours that make it a credible replacement:
 *
 *  - a pass runs at app start, and on every foreground after the throttle window;
 *  - the cold-launch start + `didBecomeActive` double-fire collapses into one pass;
 *  - the reconnect edge is never throttled, because it is the one trigger that carries new
 *    information ("the write that just failed can now succeed");
 *  - a throwing sweep never escapes and never poisons a later trigger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncDriverTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var failNext = false
        suspend fun progress() {
            calls += "progress"
            if (failNext) {
                failNext = false
                throw IllegalStateException("push failed")
            }
        }
        suspend fun annotations() {
            calls += "annotation"
        }
    }

    private fun driver(rec: Recorder, now: () -> Long, minIntervalMs: Long = 30_000L) =
        ForegroundSyncDriver(
            runProgressSweep = { rec.progress() },
            runAnnotationSweep = { rec.annotations() },
            nowMs = now,
            minIntervalMs = minIntervalMs,
        )

    @Test
    fun `app start runs a progress then annotation pass`() = runTest {
        val rec = Recorder()
        val ran = driver(rec, now = { 0L }).onAppActive()

        assertTrue(ran)
        assertEquals(listOf("progress", "annotation"), rec.calls)
    }

    @Test
    fun `a foreground inside the throttle window is coalesced into the start pass`() = runTest {
        val rec = Recorder()
        var now = 1_000L
        val d = driver(rec, now = { now })

        assertTrue(d.onAppActive())
        now = 1_000L + 29_999L
        assertFalse(d.onAppActive())

        assertEquals(listOf("progress", "annotation"), rec.calls)
    }

    @Test
    fun `a foreground after the throttle window runs a fresh pass`() = runTest {
        val rec = Recorder()
        var now = 1_000L
        val d = driver(rec, now = { now })

        assertTrue(d.onAppActive())
        now = 1_000L + 30_000L
        assertTrue(d.onAppActive())

        assertEquals(listOf("progress", "annotation", "progress", "annotation"), rec.calls)
    }

    @Test
    fun `the reconnect edge sweeps even inside the throttle window`() = runTest {
        val rec = Recorder()
        val d = driver(rec, now = { 0L })

        assertTrue(d.onAppActive())
        d.sweepProgressNow()
        d.sweepAnnotationsNow()

        assertEquals(listOf("progress", "annotation", "progress", "annotation"), rec.calls)
    }

    @Test
    fun `a throwing sweep does not escape and does not block the next trigger`() = runTest {
        val rec = Recorder()
        var now = 0L
        val d = driver(rec, now = { now })
        rec.failNext = true

        assertTrue(d.onAppActive())
        // The annotation half still ran despite the progress half throwing.
        assertEquals(listOf("progress", "annotation"), rec.calls)

        now = 60_000L
        assertTrue(d.onAppActive())
        assertEquals(listOf("progress", "annotation", "progress", "annotation"), rec.calls)
    }

    @Test
    fun `drive sweeps at start on every foreground and on every reconnect`() = runTest {
        val rec = Recorder()
        var now = 0L
        val d = driver(rec, now = { now })
        val foreground = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val isOnline = MutableStateFlow(true)

        val job = launch { d.drive(appBecameActive = foreground, isOnline = isOnline) }
        runCurrent()
        // App start.
        assertEquals(listOf("progress", "annotation"), rec.calls)

        // Foreground outside the throttle window.
        now = 60_000L
        foreground.emit(Unit)
        runCurrent()
        assertEquals(4, rec.calls.size)

        // Offline -> online edge, inside the fresh throttle window, still sweeps.
        isOnline.value = false
        runCurrent()
        isOnline.value = true
        runCurrent()
        assertEquals(6, rec.calls.size)
        assertEquals(listOf("progress", "annotation"), rec.calls.subList(4, 6))

        job.cancel()
    }
}
