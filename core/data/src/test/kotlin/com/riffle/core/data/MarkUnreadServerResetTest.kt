package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkServerProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduces the "phantom progress after mark-unread" bug at the layer that drives it: the reader's
 * reconciliation reads each ABS item's media-progress from the SERVER on open, and the audiobook peer
 * jumps the reader to `currentTime` whenever `currentTime > 0`. So an unread that doesn't zero the
 * source's audio dimension lets the old position re-appear on reopen.
 *
 * The fake ABS here encodes the real source's verified semantics (checked live against
 * media-source:13378, ABS 2.35.1): a PATCH carrying `isFinished=false` zeroes `currentTime`+`progress`;
 * `isFinished=true` sets `progress=1`; ebook fields are independent. `markFinished` must drive that
 * flag so both the ebook and audio dimensions reset to 0.
 */
class MarkUnreadServerResetTest {

    private class Record(
        var ebookLocation: String = "",
        var ebookProgress: Float = 0f,
        var currentTime: Double = 0.0,
        var duration: Double = 0.0,
        var progress: Double = 0.0,
        var isFinished: Boolean = false,
        var lastUpdate: Long = 0L,
    )

    /** Stateful ABS emulation — mirrors the live-verified `/api/me/progress/:id` PATCH semantics. */
    private class StatefulAbsApi(private var clock: Long = 1_000L) : AbsSessionApi {
        val records = mutableMapOf<String, Record>()
        fun seed(itemId: String, r: Record) { records[itemId] = r }
        private fun rec(itemId: String) = records.getOrPut(itemId) { Record() }

        override suspend fun syncEbookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload,
            token: String, insecureAllowed: Boolean,
        ): NetworkResult<Long> {
            val r = rec(libraryItemId)
            r.ebookLocation = payload.ebookLocation
            r.ebookProgress = payload.ebookProgress
            when (payload.isFinished) {
                true -> { r.isFinished = true; r.progress = 1.0 }
                false -> { r.isFinished = false; r.progress = 0.0; r.currentTime = 0.0 }
                null -> {}
            }
            r.lastUpdate = ++clock
            return NetworkResult.Success(r.lastUpdate)
        }

        override suspend fun syncAudiobookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload,
            token: String, insecureAllowed: Boolean,
        ): NetworkResult<Long> {
            val r = rec(libraryItemId)
            r.currentTime = payload.currentTime
            r.duration = payload.duration
            r.lastUpdate = ++clock
            return NetworkResult.Success(r.lastUpdate)
        }

        override suspend fun getProgress(
            baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
        ): NetworkResult<NetworkServerProgress> {
            val r = records[libraryItemId] ?: return NetworkResult.Success(NetworkServerProgress("", lastUpdate = 0L))
            return NetworkResult.Success(
                NetworkServerProgress(r.ebookLocation, r.ebookProgress, r.currentTime, r.duration, r.lastUpdate)
            )
        }
    }

    private fun repo(api: AbsSessionApi): ReadingSessionRepositoryImpl {
        val sourceRepo = object : SourceRepository {
            val source = Source("s1", SourceUrl.parse("http://localhost")!!, true, false, "")
            override fun observeAll(): Flow<List<Source>> = flowOf(listOf(source))
            override suspend fun getActive(): Source = source
            override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult = throw UnsupportedOperationException()
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
        }
        return ReadingSessionRepositoryImpl(
            catalogRegistry = InlineCatalogRegistry(testAbsCatalog(sessionApi = api, libraryApi = com.riffle.core.data.NoopLibraryApi)),
            sourceRepository = sourceRepo,
            positionStore = object : ReadingPositionStore {
            override suspend fun save(sourceId: String, itemId: String, payload: String) = Unit
            override suspend fun load(sourceId: String, itemId: String): String? = null
            override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) { }
            override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
        },
        audiobookPositionStore = object : AudiobookPositionStore {
            override suspend fun save(sourceId: String, itemId: String, payload: Double) = Unit
            override suspend fun load(sourceId: String, itemId: String): Double? = null
            override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) { }
            override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
        },
        readaloudResumeStore = object : ReadaloudResumeStore {
            override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) = Unit
            override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
            override suspend fun clear(sourceId: String, itemId: String) = Unit
        },
        libraryItemDao = FakeLibraryItemDao(),
            clock = com.riffle.core.domain.TestClock(),
        )
    }

    // The crux of the phantom: an audiobook item read to the middle. After mark-unread the source's
    // currentTime/progress MUST be 0, or the reader's audiobook peer reads currentTime>0 on reopen and
    // jumps back to it (and re-saves the progress).
    @Test
    fun `mark-unread zeroes the source audiobook currentTime so the reconcile reads no position`() = runTest {
        val api = StatefulAbsApi()
        api.seed("audio-1", Record(currentTime = 5_000.0, duration = 10_000.0, progress = 0.5, lastUpdate = 100L))

        repo(api).markFinished("audio-1", finished = false)

        val r = api.records.getValue("audio-1")
        assertEquals(0.0, r.currentTime, 1e-9)
        assertEquals(0.0, r.progress, 1e-9)
        assertEquals(false, r.isFinished)
        // The audiobook reconcile peer returns EMPTY when currentTime <= 0 — i.e. no inbound position,
        // so local (start) wins and the reader does not jump back.
        val source = (api.getProgress("", "audio-1", "tok", false) as NetworkResult.Success<NetworkServerProgress>).value
        assertEquals(0.0, source.currentTime, 1e-9)
    }

    @Test
    fun `mark-unread clears the source ebook location and progress`() = runTest {
        val api = StatefulAbsApi()
        api.seed("ebook-1", Record(ebookLocation = "epubcfi(/6/8!/4/1:0)", ebookProgress = 0.9f, lastUpdate = 100L))

        repo(api).markFinished("ebook-1", finished = false)

        val r = api.records.getValue("ebook-1")
        assertEquals("", r.ebookLocation)
        assertEquals(0f, r.ebookProgress)
        assertEquals(false, r.isFinished)
    }

    @Test
    fun `mark-read sets the source finished flag and full progress on a coupled audiobook`() = runTest {
        val api = StatefulAbsApi()
        api.seed("audio-1", Record(currentTime = 3_000.0, duration = 10_000.0, progress = 0.3, lastUpdate = 100L))

        repo(api).markFinished("audio-1", finished = true)

        val r = api.records.getValue("audio-1")
        assertEquals(true, r.isFinished)
        assertEquals(1.0, r.progress, 1e-9)
    }
}
