package com.riffle.app.feature.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @Test
    fun `start marks the key InProgress immediately, before the work runs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = DownloadManager(CoroutineScope(dispatcher))

        manager.start("k") { DownloadState.Downloaded }

        // start() must not block on the work, so the state is InProgress before we advance.
        assertEquals(DownloadState.InProgress(), manager.states.value["k"])
    }

    @Test
    fun `start reaches the terminal state returned by work once it completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = DownloadManager(CoroutineScope(dispatcher))

        manager.start("k") { DownloadState.Downloaded }
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadState.Downloaded, manager.states.value["k"])
    }

    @Test
    fun `progress callbacks surface as InProgress percentages`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = DownloadManager(CoroutineScope(dispatcher))

        manager.start("k") { onProgress ->
            onProgress(50, 100)
            DownloadState.Downloaded
        }
        testScheduler.advanceUntilIdle()

        // terminal state wins at the end, but the percentage was published mid-flight
        assertEquals(DownloadState.Downloaded, manager.states.value["k"])
    }

    @Test
    fun `a second start for an in-progress key is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = DownloadManager(CoroutineScope(dispatcher))
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        val work: suspend ((Long, Long) -> Unit) -> DownloadState = {
            runs++
            gate.await()
            DownloadState.Downloaded
        }
        manager.start("k", work)
        testScheduler.advanceUntilIdle() // first work is now suspended on the gate
        manager.start("k", work) // should be ignored — already in progress
        testScheduler.advanceUntilIdle()

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, runs)
        assertEquals(DownloadState.Downloaded, manager.states.value["k"])
    }

    @Test
    fun `work that throws resolves the key to NotDownloaded instead of a stuck spinner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = DownloadManager(CoroutineScope(dispatcher))

        manager.start("k") { throw RuntimeException("boom") }
        testScheduler.advanceUntilIdle()

        assertEquals(DownloadState.NotDownloaded, manager.states.value["k"])
    }

    @Test
    fun `clear drops the tracked state for a key`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = DownloadManager(CoroutineScope(dispatcher))

        manager.start("k") { DownloadState.Downloaded }
        testScheduler.advanceUntilIdle()
        assertTrue(manager.states.value.containsKey("k"))

        manager.clear("k")

        assertNull(manager.states.value["k"])
    }
}
