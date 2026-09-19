package com.riffle.shared.library

import com.riffle.core.domain.DefaultApplicationScope
import com.riffle.feature.library.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the iOS download manager on the iOS simulator (`:shared:iosSimulatorArm64Test`, the same
 * CI "Unit Tests" job as the XCTest suite).
 *
 * Most of `DownloadManagerTest.kt`'s scenarios are ported to Swift in
 * `iosApp/iosAppTests/DownloadsTests.swift`, which constructs the real manager. The two progress
 * scenarios cannot live there: the `(Long, Long) -> Unit` progress callback is a Kotlin
 * function-type *parameter* of `start`, and invoking the bridged Obj-C block from a
 * Swift-implemented `KotlinSuspendFunction1` wedges the xctest clone. They are covered here
 * instead, still against [IosDownloadManagerImpl] — the class Koin binds for the iOS app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosDownloadManagerImplTest {

    private fun manager(scope: CoroutineScope) =
        IosDownloadManagerImpl(DefaultApplicationScope(scope))

    /**
     * Bytes reported through the callback surface as an `InProgress` percentage, and the terminal
     * state returned by the work still wins at the end.
     */
    @Test
    fun progressCallbacksSurfaceAsInProgressPercentages() = runTest(StandardTestDispatcher()) {
        val downloads = manager(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<Int?>()

        downloads.start("k") { onProgress ->
            onProgress(50L, 100L)
            seen += (downloads.states.value["k"] as? DownloadState.InProgress)?.percent
            onProgress(75L, 100L)
            seen += (downloads.states.value["k"] as? DownloadState.InProgress)?.percent
            release.await()
            DownloadState.Downloaded
        }
        advanceUntilIdle()

        assertEquals(listOf<Int?>(50, 75), seen, "byte counts must surface as percentages")

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(DownloadState.Downloaded, downloads.states.value["k"], "terminal state must win")
    }

    /**
     * An unknown content length (total 0) must leave the spinner indeterminate rather than
     * dividing by zero or reporting a bogus percentage.
     */
    @Test
    fun unknownTotalKeepsTheSpinnerIndeterminate() = runTest(StandardTestDispatcher()) {
        val downloads = manager(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val release = CompletableDeferred<Unit>()
        var observed: DownloadState? = null

        downloads.start("k") { onProgress ->
            onProgress(10L, 0L)
            observed = downloads.states.value["k"]
            release.await()
            DownloadState.Downloaded
        }
        advanceUntilIdle()

        val inProgress = observed as? DownloadState.InProgress
        assertTrue(inProgress != null, "an unknown total must still read as InProgress")
        assertEquals(null, inProgress.percent, "an unknown total must not invent a percentage")

        release.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * Percentages are clamped into 0..100 — a server that over-reports bytes must not push the
     * progress bar past full.
     */
    @Test
    fun overReportedBytesAreClampedToOneHundred() = runTest(StandardTestDispatcher()) {
        val downloads = manager(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val release = CompletableDeferred<Unit>()
        var observed: DownloadState? = null

        downloads.start("k") { onProgress ->
            onProgress(500L, 100L)
            observed = downloads.states.value["k"]
            release.await()
            DownloadState.Downloaded
        }
        advanceUntilIdle()

        assertEquals(100, (observed as? DownloadState.InProgress)?.percent)

        release.complete(Unit)
        advanceUntilIdle()
    }
}
