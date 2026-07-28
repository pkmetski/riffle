package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.SyncSessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: bumping `lastOpenedAt` locally only is not enough — the cross-device "last read"
 * surface (ABS `mediaProgress.lastUpdate`) needs the touch-open push as well. The push used to live
 * inside `LibraryRepositoryImpl.markItemOpened`; it now lives in this use-case.
 */
class RecordItemOpenedTest {

    private class RecordingMutator : LibraryMutator {
        val openedIds = mutableListOf<String>()
        override suspend fun markItemOpened(itemId: String) { openedIds += itemId }
        override suspend fun updateReadingProgress(itemId: String, progress: Float) = Unit
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
    }

    private class RecordingSession : ReadingSessionRepository {
        val touched = mutableListOf<String>()
        override suspend fun syncProgress(itemId: String, payload: SessionPayload) = SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload) = ProgressSyncCycleResult.InSync
        override suspend fun markFinished(itemId: String, finished: Boolean) = Unit
        override suspend fun touchOpenTimestamp(itemId: String) { touched += itemId }
    }

    @Test
    fun `writes the local stamp AND pushes touchOpenTimestamp`() = runTest {
        val mutator = RecordingMutator()
        val session = RecordingSession()

        RecordItemOpened(mutator, session)("item-42")

        assertEquals(listOf("item-42"), mutator.openedIds)
        assertEquals(listOf("item-42"), session.touched)
    }
}
