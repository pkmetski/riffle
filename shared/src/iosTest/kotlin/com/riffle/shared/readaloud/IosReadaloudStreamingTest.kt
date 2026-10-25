package com.riffle.shared.readaloud

import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerSyncOutcome
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerPosition
import com.riffle.core.network.StorytellerPositionApi
import com.riffle.feature.reader.ReadaloudServerSyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosStorytellerSyncDelegateTest {

    private fun delegateFor(outcome: StorytellerSyncOutcome): IosStorytellerSyncDelegate {
        val fakeApi = object : StorytellerPositionApi {
            override suspend fun getPosition(
                baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean,
            ): NetworkResult<StorytellerPosition?> = NetworkResult.Success(null)

            override suspend fun putPosition(
                baseUrl: String, bookId: String, locatorJson: String,
                timestampMillis: Long, token: String, insecureAllowed: Boolean,
            ): NetworkResult<Unit> = NetworkResult.Success(Unit)
        }
        val fakePositionStore = object : ReadingPositionStore {
            override suspend fun save(sourceId: String, itemId: String, payload: String) {}
            override suspend fun load(sourceId: String, itemId: String): String? = null
            override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
            override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {}
            override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
        }
        val fakeSourceRepo = object : SourceRepository {
            override fun observeAll(): Flow<List<Source>> = throw UnsupportedOperationException()
            override suspend fun getActive(): Source? = null
            override suspend fun getById(sourceId: String): Source? = null
            override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>): com.riffle.core.domain.CommitSourceResult = throw UnsupportedOperationException()
            override suspend fun setActive(sourceId: String) {}
            override suspend fun remove(sourceId: String) {}
            override suspend fun getSourceVersion(sourceId: String): String? = null
        }
        val fakeTokenStorage = object : TokenStorage {
            override suspend fun saveToken(sourceId: String, token: String) {}
            override suspend fun getToken(sourceId: String): String? = null
            override suspend fun deleteToken(sourceId: String) {}
        }
        val controller = object : StorytellerPositionSyncController(
            api = fakeApi,
            positionStore = fakePositionStore,
            sourceRepository = fakeSourceRepo,
            tokenStorage = fakeTokenStorage,
        ) {
            override suspend fun runCycle(itemId: String, localLocatorJson: String) = outcome
        }
        return IosStorytellerSyncDelegate(controller)
    }

    @Test fun pulledRemoteMapsCorrectly() = runTest {
        val result = delegateFor(StorytellerSyncOutcome.PulledRemote("{}")).runCycle("x", "{}")
        assertEquals(ReadaloudServerSyncOutcome.PulledRemote("{}"), result)
    }

    @Test fun pushedLocalMapsCorrectly() = runTest {
        val result = delegateFor(StorytellerSyncOutcome.PushedLocal).runCycle("x", "{}")
        assertEquals(ReadaloudServerSyncOutcome.PushedLocal, result)
    }

    @Test fun inSyncMapsCorrectly() = runTest {
        val result = delegateFor(StorytellerSyncOutcome.InSync).runCycle("x", "{}")
        assertEquals(ReadaloudServerSyncOutcome.InSync, result)
    }

    @Test fun offlineMapsCorrectly() = runTest {
        val result = delegateFor(StorytellerSyncOutcome.Offline).runCycle("x", "{}")
        assertEquals(ReadaloudServerSyncOutcome.Offline, result)
    }
}
