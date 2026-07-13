package com.riffle.app.feature.reader

import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SyncSessionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the "sync out of the box" seam every reader constructs (#528). Behavior is delegated to
 * [com.riffle.core.domain.ProgressSyncController]; this asserts the two things the seam adds on
 * top: itemId is bound at construction (so `sync(payload)` is a one-arg call and can't be
 * mis-threaded) and the ServerWins event stream is re-exposed unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderProgressSyncSessionTest {

    private class FakeRepo(
        var result: ProgressSyncCycleResult = ProgressSyncCycleResult.InSync,
    ) : ReadingSessionRepository {
        var capturedItemId: String? = null
        var capturedPayload: SessionPayload? = null

        override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult = SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult {
            capturedItemId = itemId
            capturedPayload = payload
            return result
        }
        override suspend fun touchOpenTimestamp(itemId: String) = Unit
        override suspend fun markFinished(itemId: String, finished: Boolean) = Unit
    }

    @Test
    fun `sync binds the constructor itemId and forwards the payload to the repository`() = runTest {
        val repo = FakeRepo()
        val session = ReaderProgressSyncSession(
            itemId = "item-42",
            readingSessionRepository = repo,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
        )
        val payload = SessionPayload(ebookLocation = "cfi", ebookProgress = 0.25f)

        session.sync(payload)
        advanceUntilIdle()

        assertEquals("item-42", repo.capturedItemId)
        assertEquals(payload, repo.capturedPayload)
    }

    @Test
    fun `serverPositionEvents emits when the sync cycle returns ServerWins`() = runTest {
        val serverProgress = ServerProgress(ebookLocation = "{\"locations\":{\"position\":42}}", ebookProgress = 0.5f, lastUpdate = 1_000L)
        val repo = FakeRepo(result = ProgressSyncCycleResult.ServerWins(serverProgress))
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val session = ReaderProgressSyncSession(
            itemId = "i",
            readingSessionRepository = repo,
            scope = scope,
        )

        val collected = async { session.serverPositionEvents.first() }
        session.sync(SessionPayload("", 0f))
        advanceUntilIdle()

        assertEquals(serverProgress, collected.await())
    }
}
