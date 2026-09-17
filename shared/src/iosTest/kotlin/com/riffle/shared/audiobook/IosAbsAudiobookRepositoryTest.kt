package com.riffle.shared.audiobook

import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudioChapter
import com.riffle.core.network.NetworkAudioTrack
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkPlaybackSession
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkServerProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression for #1054: on iOS the shared catalog-backed AudiobookRepository returns null for ABS
 * sources (no ABS catalog is registered), so the player showed "Could not open audiobook".
 * [IosAbsAudiobookRepository] must open ABS sessions through the playback API directly.
 */
class IosAbsAudiobookRepositoryTest {

    private val absSource = Source(
        id = "abs-1",
        url = SourceUrl.parse("http://127.0.0.1:1234")!!,
        isActive = true,
        insecureConnectionAllowed = true,
        username = "testuser",
        type = SourceType.ABS,
    )
    private val komgaSource = absSource.copy(id = "komga-1", type = SourceType.KOMGA)

    private class FakeSourceRepository(private val sources: List<Source>) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(sources)
        override suspend fun getActive(): Source? = sources.firstOrNull { it.isActive }
        override suspend fun getById(sourceId: String): Source? = sources.firstOrNull { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeTokenStorage(private val tokens: Map<String, String>) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = tokens[sourceId]
        override suspend fun deleteToken(sourceId: String) {}
    }

    private class FakePlaybackApi(private val result: NetworkResult<NetworkPlaybackSession>) : AbsPlaybackApi {
        var lastBaseUrl: String? = null
        var lastDeviceId: String? = null
        var lastToken: String? = null
        override suspend fun openPlaybackSession(
            baseUrl: String,
            libraryItemId: String,
            deviceId: String,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<NetworkPlaybackSession> {
            lastBaseUrl = baseUrl
            lastDeviceId = deviceId
            lastToken = token
            return result
        }
    }

    private class FakeSessionApi(private val lastUpdate: Long) : AbsSessionApi {
        override suspend fun syncEbookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkEbookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<Long> = NetworkResult.Success(0L)
        override suspend fun syncAudiobookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkAudiobookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<Long> = NetworkResult.Success(0L)
        override suspend fun getProgress(
            baseUrl: String,
            libraryItemId: String,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<NetworkServerProgress> =
            NetworkResult.Success(NetworkServerProgress(ebookLocation = "", lastUpdate = lastUpdate))
    }

    private class RecordingDelegate : AudiobookRepository {
        var openCalls = 0
        override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? {
            openCalls++
            return AudiobookSession(
                trackUrls = listOf("delegate://track"),
                tracks = emptyList(),
                timeline = AudiobookTimeline(durationSec = 1.0),
                serverCurrentTimeSec = 0.0,
            )
        }
        override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) {}
    }

    private val deviceIds = object : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-42"
    }

    private fun networkSession() = NetworkPlaybackSession(
        sessionId = "session-1",
        tracks = listOf(
            NetworkAudioTrack(index = 0, startOffsetSec = 0.0, durationSec = 10.0, contentUrl = "/api/items/a/file/1", mimeType = "audio/mpeg"),
            NetworkAudioTrack(index = 1, startOffsetSec = 10.0, durationSec = 5.0, contentUrl = "api/items/a/file/2?x=1", mimeType = "audio/mpeg"),
        ),
        chapters = listOf(NetworkAudioChapter(id = 7, startSec = 0.0, endSec = 15.0, title = "Chapter 1")),
        currentTimeSec = 3.5,
        durationSec = 15.0,
    )

    private fun repository(
        playback: FakePlaybackApi,
        delegate: RecordingDelegate = RecordingDelegate(),
        tokens: Map<String, String> = mapOf(absSource.id to "tok"),
    ) = IosAbsAudiobookRepository(
        sourceRepository = FakeSourceRepository(listOf(absSource, komgaSource)),
        tokenStorage = FakeTokenStorage(tokens),
        playbackApi = playback,
        sessionApi = FakeSessionApi(lastUpdate = 999L),
        deviceIdStore = deviceIds,
        delegate = delegate,
    )

    @Test
    fun absSourceOpensSessionThroughPlaybackApi() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Success(networkSession()))
        val delegate = RecordingDelegate()

        val session = repository(playback, delegate).openSession(absSource.id, "item-audio-1")

        assertNotNull(session)
        assertEquals(0, delegate.openCalls, "ABS sources must not fall through to the catalog-backed delegate")
        assertEquals("http://127.0.0.1:1234", playback.lastBaseUrl)
        assertEquals("device-42", playback.lastDeviceId)
        assertEquals("tok", playback.lastToken)
        assertEquals(
            listOf(
                "http://127.0.0.1:1234/api/items/a/file/1?token=tok",
                "http://127.0.0.1:1234/api/items/a/file/2?x=1&token=tok",
            ),
            session.trackUrls,
        )
        assertEquals(listOf(0.0, 10.0), session.tracks.map { it.startOffsetSec })
        assertEquals(15.0, session.timeline.durationSec)
        assertEquals(listOf(0), session.timeline.chapters.map { it.index })
        assertEquals("Chapter 1", session.timeline.chapters.single().title)
        assertEquals(3.5, session.serverCurrentTimeSec)
        assertEquals(999L, session.serverLastUpdate)
    }

    @Test
    fun absSourceWithoutTokenReturnsNull() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Success(networkSession()))

        val session = repository(playback, tokens = emptyMap()).openSession(absSource.id, "item-audio-1")

        assertNull(session)
        assertNull(playback.lastBaseUrl, "No request may be made without a token")
    }

    @Test
    fun absSessionWithoutTracksReturnsNull() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Success(networkSession().copy(tracks = emptyList())))

        assertNull(repository(playback).openSession(absSource.id, "item-audio-1"))
    }

    @Test
    fun absNetworkFailureReturnsNull() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Unknown(IllegalStateException("boom")))

        assertNull(repository(playback).openSession(absSource.id, "item-audio-1"))
    }

    @Test
    fun nonAbsSourceDelegatesToCatalogRepository() = runTest {
        val playback = FakePlaybackApi(NetworkResult.Success(networkSession()))
        val delegate = RecordingDelegate()

        val session = repository(playback, delegate).openSession(komgaSource.id, "book-1")

        assertEquals(1, delegate.openCalls)
        assertEquals(listOf("delegate://track"), session?.trackUrls)
        assertNull(playback.lastBaseUrl, "Komga sources must not hit the ABS playback API")
    }

    @Test
    fun unknownSourceDelegates() = runTest {
        val delegate = RecordingDelegate()

        repository(FakePlaybackApi(NetworkResult.Success(networkSession())), delegate).openSession("missing", "x")

        assertEquals(1, delegate.openCalls, "Unknown sources fall through to the catalog-backed delegate")
    }
}
