package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudioChapter
import com.riffle.core.network.NetworkAudioTrack
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkPlaybackSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookRepositoryImplTest {

    private val source = Source(
        id = "srv", url = SourceUrl.parse("http://host:13378")!!, isActive = true,
        insecureConnectionAllowed = false, username = "u",
    )

    private fun repo(
        playback: AbsPlaybackApi,
        session: AbsSessionApi = NoopSessionApi,
        serverById: Source? = source,
        token: String? = "TKN",
    ) = AudiobookRepositoryImpl(
        playbackApi = playback,
        sessionApi = session,
        sourceRepository = FakeSourceRepository(serverById),
        tokenStorage = FakeTokenStorage(token),
    )

    @Test
    fun `openSession builds absolute tokenised track URLs and maps timeline`() = runTest {
        val playback = FakePlaybackApi(
            NetworkResult.Success(
                NetworkPlaybackSession(
                    sessionId = "ps",
                    tracks = listOf(
                        NetworkAudioTrack(0, 0.0, 100.0, "/api/items/it/file/1", "audio/mpeg"),
                        NetworkAudioTrack(1, 100.0, 200.0, "/api/items/it/file/2", "audio/mpeg"),
                    ),
                    chapters = listOf(
                        NetworkAudioChapter(0, 0.0, 100.0, "One"),
                        NetworkAudioChapter(1, 100.0, 300.0, "Two"),
                    ),
                    currentTimeSec = 42.0,
                    durationSec = 300.0,
                ),
            ),
        )

        val s = repo(playback).openSession("srv", "it")!!

        assertEquals(
            listOf(
                "http://host:13378/api/items/it/file/1?token=TKN",
                "http://host:13378/api/items/it/file/2?token=TKN",
            ),
            s.trackUrls,
        )
        assertEquals(2, s.tracks.size)
        assertEquals(100.0, s.tracks[1].startOffsetSec, 0.0)
        assertEquals(300.0, s.timeline.durationSec, 0.0)
        assertEquals(listOf("One", "Two"), s.timeline.chapters.map { it.title })
        assertEquals(42.0, s.serverCurrentTimeSec, 0.0)
    }

    @Test
    fun `openSession carries the source lastUpdate from the progress record`() = runTest {
        val playback = FakePlaybackApi(
            NetworkResult.Success(
                NetworkPlaybackSession(
                    sessionId = "ps",
                    tracks = listOf(NetworkAudioTrack(0, 0.0, 100.0, "/api/items/it/file/1", "audio/mpeg")),
                    chapters = emptyList(),
                    currentTimeSec = 42.0,
                    durationSec = 100.0,
                ),
            ),
        )
        val session = object : AbsSessionApi by NoopSessionApi {
            override suspend fun getProgress(
                baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
            ) = NetworkResult.Success(
                com.riffle.core.network.NetworkServerProgress(
                    ebookLocation = "", ebookProgress = 0f, currentTime = 42.0, duration = 100.0,
                    lastUpdate = 1700000000000L,
                ),
            )
        }

        val s = repo(playback, session).openSession("srv", "it")!!

        assertEquals(1700000000000L, s.serverLastUpdate)
    }

    @Test
    fun `openSession defaults serverLastUpdate to zero when the progress read fails`() = runTest {
        val playback = FakePlaybackApi(
            NetworkResult.Success(
                NetworkPlaybackSession(
                    sessionId = "ps",
                    tracks = listOf(NetworkAudioTrack(0, 0.0, 100.0, "/api/items/it/file/1", "audio/mpeg")),
                    chapters = emptyList(),
                    currentTimeSec = 42.0,
                    durationSec = 100.0,
                ),
            ),
        )
        // NoopSessionApi.getProgress returns NetworkError → no stamp available.
        val s = repo(playback).openSession("srv", "it")!!
        assertEquals(0L, s.serverLastUpdate)
    }

    @Test
    fun `openSession returns null when the play session has no tracks`() = runTest {
        val playback = FakePlaybackApi(
            NetworkResult.Success(
                NetworkPlaybackSession("ps", emptyList(), emptyList(), 0.0, 0.0),
            ),
        )
        assertNull(repo(playback).openSession("srv", "it"))
    }

    @Test
    fun `openSession returns null on a network error`() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Offline(RuntimeException("boom")))
        assertNull(repo(playback).openSession("srv", "it"))
    }

    @Test
    fun `openSession returns null when there is no token`() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Offline(RuntimeException("unused")))
        assertNull(repo(playback, token = null).openSession("srv", "it"))
    }

    private class FakePlaybackApi(private val result: NetworkResult<com.riffle.core.network.NetworkPlaybackSession>) : AbsPlaybackApi {
        override suspend fun openPlaybackSession(
            baseUrl: String, libraryItemId: String, deviceId: String, token: String, insecureAllowed: Boolean,
        ) = result
    }

    private class FakeTokenStorage(private val token: String?) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = token
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private class FakeSourceRepository(private val byId: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = emptyFlow()
        override suspend fun getActive(): Source? = byId
        override suspend fun getById(sourceId: String): Source? = byId
        override suspend fun authenticate(
            url: SourceUrl, username: String, password: String, insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
        ) = throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private object NoopSessionApi : AbsSessionApi {
        override suspend fun syncEbookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean,
        ) = NetworkResult.Success(0L)
        override suspend fun syncAudiobookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean,
        ) = NetworkResult.Success(0L)
        override suspend fun getProgress(
            baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
        ) = NetworkResult.Offline(RuntimeException("unused"))
    }
}
