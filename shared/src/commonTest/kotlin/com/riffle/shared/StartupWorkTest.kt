package com.riffle.shared

import com.riffle.core.common.Clock
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifact
import com.riffle.core.domain.ContentCacheArtifactScanner
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.sync.ForegroundSyncDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `LaunchedEffect` inherits the composition's dispatcher, which on iOS is the **main** thread.
 *
 * [com.riffle.core.sync.ProgressSweep] performs no dispatcher switch of its own — on Android its
 * only caller is a WorkManager worker that is already off the main thread — so once #1071 §14
 * started driving it from `RiffleAppRoot`, its database reads and network writes landed on the UI
 * thread at first composition, competing with first paint.
 *
 * Reverting `runStartupWork`'s `withContext(io)` turns [theProgressSweepRunsOnTheIoDispatcher]
 * red: the sweep then reports the caller's dispatcher instead of the one it was handed.
 */
class StartupWorkTest {

    private object ZeroClock : Clock {
        override fun nowMs(): Long = 0L
        override fun nowNs(): Long = 0L
    }

    private class OneDispatcher(private val d: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher get() = d
        override val mainImmediate: CoroutineDispatcher get() = d
        override val io: CoroutineDispatcher get() = d
        override val default: CoroutineDispatcher get() = d
    }

    private object AutoClearOff : ContentCacheSettingsStore {
        override val autoClear: Flow<ContentCacheAutoClear> = flowOf(ContentCacheAutoClear.Off)
        override suspend fun setAutoClear(value: ContentCacheAutoClear) = Unit
    }

    private object NoAccessRecords : ContentCacheAccessStore {
        override suspend fun markAccessed(key: ContentCacheKey) = Unit
        override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) = Unit
        override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = null
        override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> = emptyMap()
        override suspend fun forget(key: ContentCacheKey) = Unit
    }

    private class Scanner(private val onList: () -> List<ContentCacheArtifact>) : ContentCacheArtifactScanner {
        override fun listArtifacts(): List<ContentCacheArtifact> = onList()
        override fun delete(artifact: ContentCacheArtifact): Boolean = false
    }

    private fun cleaner(scanner: ContentCacheArtifactScanner) = ContentCacheCleaner(
        settingsStore = AutoClearOff,
        accessStore = NoAccessRecords,
        artifactScanner = scanner,
        clock = ZeroClock,
        dispatchers = OneDispatcher(UnconfinedTestDispatcher()),
    )

    @Test
    fun theProgressSweepRunsOnTheIoDispatcher() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        var sweptOn: ContinuationInterceptor? = null

        runStartupWork(
            io = io,
            contentCacheCleaner = cleaner(Scanner { emptyList() }),
            syncDriver = ForegroundSyncDriver(
                runProgressSweep = { sweptOn = currentCoroutineContext()[ContinuationInterceptor] },
                nowMs = { 0L },
            ),
            appBecameActive = emptyFlow(),
            isOnline = emptyFlow(),
        )

        assertNotNull(sweptOn, "the app-start pass must have run")
        assertSame(
            io,
            sweptOn,
            "ProgressSweep does its own database and network work with no dispatcher switch, " +
                "so it must not inherit the composition's main dispatcher",
        )
    }

    @Test
    fun aFailingCacheSweepDoesNotStopTheProgressSweep() = runTest {
        var swept = false

        runStartupWork(
            io = UnconfinedTestDispatcher(testScheduler),
            contentCacheCleaner = cleaner(Scanner { throw IllegalStateException("disk full") }),
            syncDriver = ForegroundSyncDriver(runProgressSweep = { swept = true }, nowMs = { 0L }),
            appBecameActive = emptyFlow(),
            isOnline = emptyFlow(),
        )

        assertSame(true, swept, "a failed cache sweep must not stop the progress sweep")
    }

    @Test
    fun theStartupJobsWaitForTheFirstFrameBeforeCompetingWithIt() = runTest {
        var sweptAtMs = -1L

        runStartupWork(
            io = UnconfinedTestDispatcher(testScheduler),
            contentCacheCleaner = cleaner(Scanner { emptyList() }),
            syncDriver = ForegroundSyncDriver(
                runProgressSweep = { sweptAtMs = testScheduler.currentTime },
                nowMs = { 0L },
            ),
            appBecameActive = emptyFlow(),
            isOnline = emptyFlow(),
        )

        assertTrue(
            sweptAtMs >= STARTUP_WORK_DELAY_MS,
            "startup work must yield the launch to the UI; swept at ${sweptAtMs}ms",
        )
    }
}
