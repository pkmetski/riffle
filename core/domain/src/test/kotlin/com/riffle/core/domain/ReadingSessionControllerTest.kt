package com.riffle.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private val payload = SessionPayload("epubcfi(/6/4!/4/1:0)", 0.5f)

    private fun fakeRepo(
        syncResult: SyncSessionResult = SyncSessionResult.Success,
    ) = object : ReadingSessionRepository {
        override suspend fun syncProgress(itemId: String, payload: SessionPayload) = syncResult
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult = ProgressSyncCycleResult.InSync
    }

    @Test
    fun `sync calls repository with correct itemId and payload`() = scope.runTest {
        var syncedItemId: String? = null
        var syncedPayload: SessionPayload? = null
        val controller = ReadingSessionController(
            object : ReadingSessionRepository {
                override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult {
                    syncedItemId = itemId
                    syncedPayload = payload
                    return SyncSessionResult.Success
                }
                override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult = ProgressSyncCycleResult.InSync
            },
            scope,
        )
        controller.sync("item-1", payload)
        advanceUntilIdle()
        assert(syncedItemId == "item-1")
        assert(syncedPayload == payload)
    }

    @Test
    fun `sync invokes onSyncError callback on NetworkError`() = scope.runTest {
        var errorInvoked = false
        val controller = ReadingSessionController(
            fakeRepo(syncResult = SyncSessionResult.NetworkError(RuntimeException("network"))),
            scope,
            onSyncError = { errorInvoked = true },
        )
        controller.sync("item-1", payload)
        advanceUntilIdle()
        assertTrue(errorInvoked)
    }

    @Test
    fun `sync does not invoke onSyncError on Success`() = scope.runTest {
        var errorInvoked = false
        val controller = ReadingSessionController(
            fakeRepo(syncResult = SyncSessionResult.Success),
            scope,
            onSyncError = { errorInvoked = true },
        )
        controller.sync("item-1", payload)
        advanceUntilIdle()
        assertFalse(errorInvoked)
    }

    @Test
    fun `multiple syncs all reach the repository`() = scope.runTest {
        var callCount = 0
        val controller = ReadingSessionController(
            object : ReadingSessionRepository {
                override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult {
                    callCount++
                    return SyncSessionResult.Success
                }
                override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult = ProgressSyncCycleResult.InSync
            },
            scope,
        )
        controller.sync("item-1", payload)
        controller.sync("item-1", payload)
        controller.sync("item-1", payload)
        advanceUntilIdle()
        assert(callCount == 3)
    }
}
