package com.riffle.feature.reader

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.sync.AnnotationSyncStatusStore
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync-lifecycle pins for [AnnotationSyncCoordinator] — the half of the reader's annotation
 * session that owns book identity, namespace gating and the single-flight live-pull job.
 *
 * Moved here from `app`'s `AnnotationSessionTest` (issue #1066) when the lifecycle was extracted
 * to commonMain, so the same assertions run on JVM and on iOS. `AnnotationSession` delegates
 * wholly to this class, so every claim below still describes the reader's behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncCoordinatorTest {

    /** Minimal [ApplicationScope] mirroring `DefaultApplicationScope` for the close-time flush. */
    private class TestApplicationScope(private val scope: CoroutineScope) : ApplicationScope {
        override val coroutineScope: CoroutineScope = scope
        override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
            scope.launch(block = block)
        override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
            scope.async(block = block).await()
    }

    /**
     * Lightweight fake for sync operations. The coordinator interacts with the controller
     * through four operations: syncOnOpen, startLiveSync, scheduleSync, syncOnClose.
     */
    private class FakeSyncOps {
        var syncOnOpenCalled = false
        var syncOnCloseCalled = false
        var scheduleDebounceCount = 0
        var startLiveSyncCalled = false
        var lastLiveSyncJob: Job? = null

        fun makeSyncOnOpen(): suspend (String, String, String) -> Unit = { _, _, _ ->
            syncOnOpenCalled = true
        }

        fun makeScheduleDebounce(): (String, String, String) -> Unit = { _, _, _ ->
            scheduleDebounceCount++
        }

        fun makeStartLiveSync(scope: CoroutineScope): (String, String, String) -> Job = { _, _, _ ->
            startLiveSyncCalled = true
            scope.launch { delay(Long.MAX_VALUE) }
                .also { lastLiveSyncJob = it }
        }

        fun makeSyncOnClose(): suspend (String, String, String) -> Unit = { _, _, _ ->
            syncOnCloseCalled = true
        }
    }

    private fun makeCoordinator(
        syncOps: FakeSyncOps,
        scope: CoroutineScope,
        flushScope: ProgressFlushScope = ProgressFlushScope(
            TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
        ),
    ) = AnnotationSyncCoordinator(
        scope = scope,
        progressFlushScope = flushScope,
        startLiveSync = syncOps.makeStartLiveSync(scope),
        scheduleSync = syncOps.makeScheduleDebounce(),
        syncOnOpen = syncOps.makeSyncOnOpen(),
        syncOnClose = syncOps.makeSyncOnClose(),
    )

    /**
     * `AnnotationSession.bind` calls these two in this order, with its own observers wired up in
     * between. Mirrors the single `session.bind(...)` the tests used before the extraction.
     */
    private fun defaultBind(
        coordinator: AnnotationSyncCoordinator,
        sourceId: String = "srv1",
        namespace: String = "ns1",
        itemId: String = "item1",
    ) {
        coordinator.bind(sourceId = sourceId, namespace = namespace, itemId = itemId)
        coordinator.startSyncForBoundBook()
    }

    /**
     * Test 5: syncBanner reflects annotationStatusStore states (Syncing/Synced/Failed)
     */
    @Test
    fun `syncBanner reflects annotationStatusStore states`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val statusStore = AnnotationSyncStatusStore()
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)
        val syncBanner = coordinator.syncBanner(statusStore)

        // Initial: NeverRun → null banner
        assertNull(syncBanner.value)

        statusStore.report(CycleOutcome.Success(1000L))
        assertEquals(AnnotationSyncBanner.Synced, syncBanner.value)

        statusStore.report(CycleOutcome.Failed.Network(2000L, "timeout"))
        assertTrue(syncBanner.value is AnnotationSyncBanner.Failed)

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 6: bind triggers syncOnOpen and startLiveSync
     */
    @Test
    fun `bind triggers syncOnOpen and startLiveSync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator)

        assertTrue(syncOps.syncOnOpenCalled, "syncOnOpen should be called on bind")
        assertTrue(syncOps.startLiveSyncCalled, "startLiveSync should be called on bind")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 7: onBookClosed triggers syncOnClose and cancels live-sync job
     */
    @Test
    fun `onBookClosed triggers syncOnClose and cancels live-sync job`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        // Use a real ProgressFlushScope backed by a test dispatcher
        val flushScope = ProgressFlushScope(
            TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
        )
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope, flushScope = flushScope)

        defaultBind(coordinator)

        val liveSyncJob = syncOps.lastLiveSyncJob
        assertFalse(liveSyncJob?.isCancelled ?: true, "Live-sync job should be active before close")

        coordinator.onBookClosed()

        assertTrue(syncOps.syncOnCloseCalled, "syncOnClose should be called on book closed")
        assertTrue(liveSyncJob?.isCancelled == true, "Live-sync job should be cancelled after onBookClosed")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 8: live-sync job is single-flight per book (rebind cancels the previous job)
     */
    @Test
    fun `live-sync job is single-flight per book`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator)
        val firstJob = syncOps.lastLiveSyncJob

        // Rebind to a different item — should cancel the first live-sync job
        defaultBind(coordinator, sourceId = "srv1", namespace = "ns1", itemId = "item2")
        val secondJob = syncOps.lastLiveSyncJob

        assertTrue(firstJob?.isCancelled == true, "Previous live-sync job should be cancelled on rebind")
        assertTrue(secondJob?.isActive == true, "New live-sync job should be active")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    /**
     * Test 11: onReaderClosed cancels the live-sync job (regression: the original VM cancelled
     * annotationLiveSyncJob in onReaderClosed; the extraction removed that call).
     */
    @Test
    fun `onReaderClosed cancels live-sync job`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator)

        val liveSyncJob = syncOps.lastLiveSyncJob
        assertTrue(liveSyncJob?.isActive == true, "Live-sync job should be active after bind")

        coordinator.onReaderClosed()

        assertTrue(liveSyncJob?.isCancelled == true, "Live-sync job should be cancelled after onReaderClosed")

        sessionScope.coroutineContext[Job]?.cancel()
    }

    // These tests pin the "bind with empty namespace, resolve later" contract that the
    // reader-open path relies on to start the annotation Flow observer BEFORE _state = Ready.
    // Flipping any of them means the reordered cold-open pipeline in EpubReaderViewModel.
    // onOpenReady would either regress sync (no syncOnOpen when namespace is late-supplied) or
    // regress the eager subscription (bind gated behind ensureSyncNamespace's IO wait again).
    // The companion "…still starts the annotation Flow observer" case stays in `app`'s
    // AnnotationSessionTest, because the observer it asserts on lives in AnnotationSession.

    @Test
    fun `bind with empty namespace does NOT trigger syncOnOpen or startLiveSync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator, namespace = "")

        assertFalse(syncOps.syncOnOpenCalled, "empty-namespace bind must not run syncOnOpen")
        assertFalse(syncOps.startLiveSyncCalled, "empty-namespace bind must not start live-sync loop")
    }

    @Test
    fun `updateNamespace after empty-namespace bind kicks off syncOnOpen and startLiveSync`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator, namespace = "")
        assertFalse(syncOps.syncOnOpenCalled)
        assertFalse(syncOps.startLiveSyncCalled)

        coordinator.updateNamespace("ns-real")

        assertTrue(syncOps.syncOnOpenCalled, "updateNamespace(non-empty) must run syncOnOpen")
        assertTrue(syncOps.startLiveSyncCalled, "updateNamespace(non-empty) must start live-sync")
    }

    // Race-window regression: any user mutation between bind(namespace="") and updateNamespace
    // (createHighlight, delete, recolour, note-edit) hits `boundNamespace ?: return` in
    // scheduleSync and silently doesn't queue a push. The Room write persists but the remote
    // push is missed. updateNamespace must fire a scheduleSync on the null→non-null transition
    // to flush any pending queued writes. Flips red if that flush is removed.
    @Test
    fun `updateNamespace bootstrap fires scheduleSync to flush pre-namespace-window writes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator, namespace = "")
        // Nothing scheduled yet.
        assertEquals(0, syncOps.scheduleDebounceCount)

        coordinator.updateNamespace("ns-real")

        assertEquals(
            1,
            syncOps.scheduleDebounceCount,
            "null→non-null transition must nudge scheduleSync to flush the race window",
        )
    }

    @Test
    fun `updateNamespace with null after empty-namespace bind stays no-op`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher)
        val syncOps = FakeSyncOps()
        val coordinator = makeCoordinator(syncOps = syncOps, scope = sessionScope)

        defaultBind(coordinator, namespace = "")
        coordinator.updateNamespace(null)

        // Local-only source: ensureSyncNamespace returns null → session stays in no-sync mode.
        assertFalse(syncOps.syncOnOpenCalled, "updateNamespace(null) must not run syncOnOpen")
        assertFalse(syncOps.startLiveSyncCalled, "updateNamespace(null) must not start live-sync")
    }
}
