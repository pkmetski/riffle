package com.riffle.core.data

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkSyncSessionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProgressSyncCycleTest {

    // --- fakes ---

    private class FakePositionStore(
        var localUpdatedAt: Long = 0L,
        private var storedCfi: String? = null,
    ) : ReadingPositionStore {
        var updatedTimestamp: Long? = null
        var updatedServerId: String? = null
        var savedPayload: String? = null
        var saveCalled = false
        override suspend fun save(serverId: String, itemId: String, payload: String) {
            saveCalled = true
            savedPayload = payload
            storedCfi = payload
        }
        override suspend fun load(serverId: String, itemId: String): String? = storedCfi
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = localUpdatedAt
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
            updatedServerId = serverId
            updatedTimestamp = millis
            localUpdatedAt = millis
        }
    }

    private class FakeSessionApi(
        private val getResult: NetworkGetProgressResult,
        private val patchResult: NetworkSyncSessionResult = NetworkSyncSessionResult.Success(0L),
    ) : AbsSessionApi {
        var patchCallCount = 0
        var lastEbookPayload: NetworkEbookProgressPayload? = null
        override suspend fun syncEbookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload,
            token: String, insecureAllowed: Boolean,
        ): NetworkSyncSessionResult {
            patchCallCount++
            lastEbookPayload = payload
            return patchResult
        }
        override suspend fun syncAudiobookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload,
            token: String, insecureAllowed: Boolean,
        ): NetworkSyncSessionResult = patchResult
        override suspend fun getProgress(
            baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
        ): NetworkGetProgressResult = getResult
    }

    private class FakeAudiobookPositionStore : com.riffle.core.domain.AudiobookPositionStore {
        var savedPayload: Double? = null
        var saveCalled = false
        var updatedTimestamp: Long? = null
        override suspend fun save(serverId: String, itemId: String, payload: Double) {
            saveCalled = true
            savedPayload = payload
        }
        override suspend fun load(serverId: String, itemId: String): Double? = null
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
            updatedTimestamp = millis
        }
    }

    private class FakeReadaloudResumeStore : com.riffle.core.domain.ReadaloudResumeStore {
        var clearCalled = false
        override suspend fun save(serverId: String, itemId: String, position: com.riffle.core.domain.ReadaloudResumePosition) = Unit
        override suspend fun load(serverId: String, itemId: String): com.riffle.core.domain.ReadaloudResumePosition? = null
        override suspend fun clear(serverId: String, itemId: String) { clearCalled = true }
    }

    private fun buildRepo(
        api: AbsSessionApi,
        positionStore: ReadingPositionStore,
        audiobookPositionStore: com.riffle.core.domain.AudiobookPositionStore = FakeAudiobookPositionStore(),
        readaloudResumeStore: com.riffle.core.domain.ReadaloudResumeStore = FakeReadaloudResumeStore(),
    ) =
        ReadingSessionRepositoryImpl(
            api = api,
            serverRepository = object : ServerRepository {
                val server = Server("s1", ServerUrl.parse("http://localhost")!!, true, false, "")
                override fun observeAll(): Flow<List<Server>> = flowOf(listOf(server))
                override suspend fun getActive(): Server = server
                override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult = throw UnsupportedOperationException()
                override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult = throw UnsupportedOperationException()
                override suspend fun setActive(serverId: String) = Unit
                override suspend fun remove(serverId: String) = Unit
                override suspend fun getServerVersion(serverId: String): String? = null
            },
            tokenStorage = object : TokenStorage {
                override suspend fun saveToken(serverId: String, token: String) = Unit
                override suspend fun getToken(serverId: String): String? = "tok"
                override suspend fun deleteToken(serverId: String) = Unit
            },
            positionStore = positionStore,
            audiobookPositionStore = audiobookPositionStore,
            readaloudResumeStore = readaloudResumeStore,
        )

    private val payload = SessionPayload("epubcfi(/6/4!/4/1:0)", 0.25f)

    // --- tests ---

    @Test
    fun `server-newer returns ServerWins with server ebookLocation and updates localUpdatedAt`() = runTest {
        val localTs = 1_000L
        val serverTs = 2_000L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(
                NetworkServerProgress("epubcfi(/6/8!/4/1:0)", lastUpdate = serverTs)
            )
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        assertEquals("epubcfi(/6/8!/4/1:0)", (result as ProgressSyncCycleResult.ServerWins).serverProgress.ebookLocation)
        assertEquals(serverTs, result.serverProgress.lastUpdate)
        assertEquals(serverTs, positionStore.updatedTimestamp)
        assertEquals(0, api.patchCallCount)
    }

    @Test
    fun `local-newer sends PATCH and returns LocalWins, updates localUpdatedAt from response`() = runTest {
        val localTs = 3_000L
        val serverTs = 1_000L
        val patchResponseTs = 3_100L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = serverTs)),
            patchResult = NetworkSyncSessionResult.Success(patchResponseTs),
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertEquals(1, api.patchCallCount)
        assertEquals(patchResponseTs, positionStore.updatedTimestamp)
    }

    @Test
    fun `equal timestamps returns InSync without PATCH`() = runTest {
        val ts = 2_000L
        val positionStore = FakePositionStore(localUpdatedAt = ts)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("cfi", lastUpdate = ts))
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.InSync)
        assertEquals(0, api.patchCallCount)
    }

    @Test
    fun `GET failure returns Offline without PATCH and leaves localUpdatedAt unchanged`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 5_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unreachable"))
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.Offline)
        assertEquals(0, api.patchCallCount)
        assertEquals(null, positionStore.updatedTimestamp)
    }

    @Test
    fun `local-newer with PATCH returning zero lastUpdate does NOT corrupt localUpdatedAt to zero`() = runTest {
        val localTs = 3_000L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = 1_000L)),
            patchResult = NetworkSyncSessionResult.Success(0L),
        )

        buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(positionStore.updatedTimestamp != null)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }

    @Test
    fun `local-newer with PATCH returning positive lastUpdate sets localUpdatedAt correctly`() = runTest {
        val localTs = 3_000L
        val patchResponseTs = 3_100L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = 1_000L)),
            patchResult = NetworkSyncSessionResult.Success(patchResponseTs),
        )

        buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertEquals(patchResponseTs, positionStore.updatedTimestamp)
    }

    @Test
    fun `local-newer with zero PATCH lastUpdate still returns LocalWins`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 3_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = 1_000L)),
            patchResult = NetworkSyncSessionResult.Success(0L),
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
    }

    @Test
    fun `markFinished true PATCHes ebookProgress 1 and isFinished true, keeps saved position`() = runTest {
        // Read = mark complete on BOTH dimensions. isFinished=true is what flips the audio
        // `progress` to 100% (bug 1: "marking read doesn't mark the related audiobook").
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L, storedCfi = "epubcfi(/6/8!/4/1:0)")
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
            patchResult = NetworkSyncSessionResult.Success(2_000L),
        )
        val repo = buildRepo(api, positionStore)

        repo.markFinished("item-1", finished = true)

        assertEquals(1, api.patchCallCount)
        assertEquals(1.0f, api.lastEbookPayload?.ebookProgress)
        assertEquals(true, api.lastEbookPayload?.isFinished)
        // Read keeps the page the user reached.
        assertEquals("epubcfi(/6/8!/4/1:0)", api.lastEbookPayload?.ebookLocation)
        assertFalse(positionStore.saveCalled)
        assertNotNull(positionStore.updatedTimestamp)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }

    @Test
    fun `markFinished false PATCHes ebookProgress 0 and isFinished false, clears the saved position`() = runTest {
        // Unread = reset BOTH dimensions to 0. isFinished=false zeroes the audio currentTime/progress
        // on the server so it can't re-shadow the 0 ebookProgress on the next refresh (bug 2:
        // "marking unread restores an old progress").
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L, storedCfi = "epubcfi(/6/8!/4/1:0)")
        val audiobookStore = FakeAudiobookPositionStore()
        val resumeStore = FakeReadaloudResumeStore()
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
            patchResult = NetworkSyncSessionResult.Success(2_000L),
        )
        val repo = buildRepo(api, positionStore, audiobookStore, resumeStore)

        repo.markFinished("item-1", finished = false)

        assertEquals(1, api.patchCallCount)
        assertEquals(0.0f, api.lastEbookPayload?.ebookProgress)
        assertEquals(false, api.lastEbookPayload?.isFinished)
        assertEquals("", api.lastEbookPayload?.ebookLocation)
        // Unread clears the local saved position so the reader reopens at the start.
        assertTrue(positionStore.saveCalled)
        assertEquals("", positionStore.savedPayload)
        assertNotNull(positionStore.updatedTimestamp)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
        // ...and wipes the other stores that could otherwise restore the position on open.
        assertTrue(audiobookStore.saveCalled)
        assertEquals(0.0, audiobookStore.savedPayload)
        assertNotNull(audiobookStore.updatedTimestamp)
        assertTrue(resumeStore.clearCalled)
    }

    @Test
    fun `markFinished true does not wipe audiobook or readaloud-resume stores`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L, storedCfi = "epubcfi(/6/8!/4/1:0)")
        val audiobookStore = FakeAudiobookPositionStore()
        val resumeStore = FakeReadaloudResumeStore()
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
            patchResult = NetworkSyncSessionResult.Success(2_000L),
        )
        val repo = buildRepo(api, positionStore, audiobookStore, resumeStore)

        repo.markFinished("item-1", finished = true)

        assertFalse(audiobookStore.saveCalled)
        assertFalse(resumeStore.clearCalled)
    }

    // --- 404-equivalent (server has no progress record) ---

    @Test
    fun `server returns no-progress (lastUpdate=0) with no local data returns InSync`() = runTest {
        // Both local and server have lastUpdate=0 — nothing to push or pull.
        val positionStore = FakePositionStore(localUpdatedAt = 0L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("", lastUpdate = 0L))
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.InSync)
        assertEquals(0, api.patchCallCount)
    }

    @Test
    fun `server returns no-progress (lastUpdate=0) with local data returns LocalWins and sends PATCH`() = runTest {
        // Server has no record (mapped 404), local has been read — must push.
        val positionStore = FakePositionStore(localUpdatedAt = 5_000L)
        val patchResponseTs = 5_100L
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("", lastUpdate = 0L)),
            patchResult = NetworkSyncSessionResult.Success(patchResponseTs),
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertEquals(1, api.patchCallCount)
        assertEquals(patchResponseTs, positionStore.updatedTimestamp)
    }

    @Test
    fun `server returns no-progress and ServerWins result carries ebookProgress field`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 0L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(
                NetworkServerProgress("epubcfi(/6/4!/4/2/1:5)", ebookProgress = 0.42f, lastUpdate = 9_000L)
            )
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        val serverProgress = (result as ProgressSyncCycleResult.ServerWins).serverProgress
        assertEquals("epubcfi(/6/4!/4/2/1:5)", serverProgress.ebookLocation)
        assertEquals(0.42f, serverProgress.ebookProgress, 0.001f)
        assertEquals(9_000L, serverProgress.lastUpdate)
    }

    @Test
    fun `markFinished bumps localUpdatedAt even when PATCH fails`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
            patchResult = NetworkSyncSessionResult.NetworkError(IOException("network down")),
        )
        val repo = buildRepo(api, positionStore)

        repo.markFinished("item-1", finished = true)

        assertEquals(1, api.patchCallCount)
        assertNotNull(positionStore.updatedTimestamp)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }

    // --- touchOpenTimestamp ---

    @Test
    fun `touchOpenTimestamp PATCHes back the server's current ebookLocation without writing local timestamp`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 0L)
        val recording = object : AbsSessionApi {
            var patchPayload: NetworkEbookProgressPayload? = null
            override suspend fun syncEbookProgress(
                baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload,
                token: String, insecureAllowed: Boolean,
            ): NetworkSyncSessionResult {
                patchPayload = payload
                return NetworkSyncSessionResult.Success(7_777L)
            }
            override suspend fun syncAudiobookProgress(
                baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload,
                token: String, insecureAllowed: Boolean,
            ) = NetworkSyncSessionResult.Success(0L)
            override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean) =
                NetworkGetProgressResult.Success(NetworkServerProgress("server-cfi", ebookProgress = 0.42f, lastUpdate = 3_000L))
        }

        buildRepo(recording, positionStore).touchOpenTimestamp("item-1")

        assertEquals("server-cfi", recording.patchPayload?.ebookLocation)
        assertEquals(0.42f, recording.patchPayload?.ebookProgress)
        // Local timestamps stay untouched — this is what allows the next sync cycle to
        // recognise the server as the source of truth and pull the saved position.
        assertEquals(null, positionStore.updatedTimestamp)
        assertEquals(0L, positionStore.localUpdatedAt)
    }

    @Test
    fun `touchOpenTimestamp leaves localUpdatedAt untouched so the next sync cycle pulls server progress`() = runTest {
        // Regression: touchOpenTimestamp used to bump local's updatedAt to the server's new
        // lastUpdate without writing the server's cfi/progress locally. The empty local cfi
        // + matching timestamp made the next runSyncCycle return InSync — the reader opened
        // at page 1 and any subsequent local save overwrote the real server position.
        // The contract is now: touchOpenTimestamp doesn't touch local timestamps, so the
        // first sync cycle after it sees server > local and ServerWins fires, restoring the
        // saved position.
        val positionStore = FakePositionStore(localUpdatedAt = 0L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(
                NetworkServerProgress("server-cfi", ebookProgress = 0.42f, lastUpdate = 3_000L)
            ),
            patchResult = NetworkSyncSessionResult.Success(7_777L),
        )
        val repo = buildRepo(api, positionStore)

        repo.touchOpenTimestamp("item-1")
        val result = repo.runSyncCycle("item-1", payload = SessionPayload("", 0f))

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        assertEquals("server-cfi", (result as ProgressSyncCycleResult.ServerWins).serverProgress.ebookLocation)
    }

    @Test
    fun `touchOpenTimestamp is a no-op when getProgress fails`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("offline")),
        )

        buildRepo(api, positionStore).touchOpenTimestamp("item-1")

        assertEquals(0, api.patchCallCount)
        assertEquals(null, positionStore.updatedTimestamp)
    }
}
