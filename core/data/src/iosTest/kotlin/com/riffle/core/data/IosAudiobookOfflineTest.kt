package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.models.AudiobookTrackSpan
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS counterpart to core:data's androidHostTest `AudiobookDownloadRepositoryImplTest` and
 * `AudiobookCacheRepositoryImplTest` (issue #1065). Same scenarios, same assertions, driving the
 * real iOS code path: NSFileManager directories, the Ktor streaming download, and the shared
 * [AudiobookDownloadManifest] on disk. MockEngine stands in for the JVM tests' MockWebServer.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudiobookOfflineTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    /** FileStore rooted at a fresh temp dir so downloads and cache never touch the real app dirs. */
    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "audiobook_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            IosAudiobookFiles.mkdirs(base)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private class RecordingAvailabilityEvents : LocalAvailabilityEvents {
        val notified = mutableListOf<Pair<String, String>>()
        private val _changes = MutableSharedFlow<StoredItemRef>(extraBufferCapacity = 8)
        override val changes: SharedFlow<StoredItemRef> get() = _changes
        override fun notifyChanged(sourceId: String, itemId: String) {
            notified += sourceId to itemId
        }
    }

    private class FakeAudiobookRepository(
        private val session: AudiobookSession?,
        private val sizeBytes: Long? = null,
    ) : AudiobookRepository {
        var openSessionCalls = 0
        override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? {
            openSessionCalls++
            return session
        }
        override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) = Unit
        override suspend fun downloadSizeBytes(sourceId: String, itemId: String): Long? = sizeBytes
    }

    private fun session(trackUrls: List<String>, downloadTrackUrls: List<String>? = null) = AudiobookSession(
        trackUrls = trackUrls,
        tracks = trackUrls.mapIndexed { i, _ -> AudiobookTrackSpan(i, i * 10.0, 10.0) },
        timeline = AudiobookTimeline(
            durationSec = trackUrls.size * 10.0,
            chapters = listOf(AudiobookChapter(0, 0.0, trackUrls.size * 10.0, "Chapter 1")),
        ),
        serverCurrentTimeSec = 0.0,
        downloadTrackUrls = downloadTrackUrls,
    )

    private fun mockClient(body: ByteArray = ByteArray(1024) { 7 }, record: MutableList<String>? = null) =
        HttpClient(
            MockEngine { request ->
                record?.add(request.url.toString())
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Length", body.size.toString()),
                )
            },
        )

    private fun downloadRepo(
        fileStore: FileStore,
        client: HttpClient = mockClient(),
        audiobookRepository: AudiobookRepository = FakeAudiobookRepository(null),
        events: LocalAvailabilityEvents = RecordingAvailabilityEvents(),
    ) = IosAudiobookDownloadRepositoryImpl(
        audiobookRepository = audiobookRepository,
        trackDownloader = IosAudiobookTrackDownloader(client, IosDispatcherProvider),
        fileStore = fileStore,
        dispatchers = IosDispatcherProvider,
        localAvailabilityEvents = events,
    )

    private fun cacheRepo(
        fileStore: FileStore,
        client: HttpClient = mockClient(),
        events: LocalAvailabilityEvents = RecordingAvailabilityEvents(),
    ) = IosAudiobookCacheRepositoryImpl(
        trackDownloader = IosAudiobookTrackDownloader(client, IosDispatcherProvider),
        fileStore = fileStore,
        dispatchers = IosDispatcherProvider,
        localAvailabilityEvents = events,
        minInterTrackDelayMs = 0L,
        maxInterTrackDelayMs = 0L,
    )

    // ── Download repository ───────────────────────────────────────────────────

    @Test
    fun `isDownloaded reflects the manifest presence`() = runTest {
        val fileStore = TempFileStore()
        val repo = downloadRepo(fileStore, audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1"))))

        assertFalse(repo.isDownloaded("s1", "i1"))
        assertEquals(AudiobookDownloadResult.Success, repo.download("s1", "i1") { _, _ -> })
        assertTrue(repo.isDownloaded("s1", "i1"))
    }

    @Test
    fun `download streams every track and writes the manifest last`() = runTest {
        val fileStore = TempFileStore()
        val requested = mutableListOf<String>()
        val repo = downloadRepo(
            fileStore,
            client = mockClient(record = requested),
            audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1", "https://x/2"))),
        )

        assertEquals(AudiobookDownloadResult.Success, repo.download("s1", "i1") { _, _ -> })

        assertEquals(listOf("https://x/1", "https://x/2"), requested)
        val dir = "${fileStore.resolve(NS_AUDIOBOOK_DOWNLOADS)}/s1/i1"
        assertTrue(IosAudiobookFiles.exists("$dir/track-0"), "track-0 should be on disk")
        assertTrue(IosAudiobookFiles.exists("$dir/track-1"), "track-1 should be on disk")
        assertTrue(IosAudiobookFiles.exists("$dir/manifest.json"), "manifest should be written")
    }

    @Test
    fun `localSession reconstructs file URLs spans and timeline from the manifest`() = runTest {
        val fileStore = TempFileStore()
        val repo = downloadRepo(
            fileStore,
            audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1", "https://x/2"))),
        )
        repo.download("s1", "i1") { _, _ -> }

        val local = repo.localSession("s1", "i1")

        assertTrue(local != null)
        assertEquals(2, local.trackUrls.size)
        assertTrue(local.trackUrls.all { it.startsWith("file://") }, "expected file URLs, got ${local.trackUrls}")
        assertEquals(listOf(0, 1), local.tracks.map { it.index })
        assertEquals(20.0, local.timeline.durationSec)
        assertEquals("Chapter 1", local.timeline.chapters.single().title)
        assertEquals(0.0, local.serverCurrentTimeSec)
    }

    @Test
    fun `localSession is null when not downloaded`() {
        assertNull(downloadRepo(TempFileStore()).localSession("s1", "i1"))
    }

    @Test
    fun `download reports progress monotonically across tracks`() = runTest {
        val fileStore = TempFileStore()
        val repo = downloadRepo(
            fileStore,
            audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1", "https://x/2"))),
        )

        val seen = mutableListOf<Long>()
        repo.download("s1", "i1") { downloaded, _ -> seen += downloaded }

        assertTrue(seen.isNotEmpty(), "expected progress callbacks")
        assertEquals(seen.sorted(), seen, "progress must never go backwards: $seen")
        assertEquals(2048L, seen.last(), "two 1 KiB tracks should total 2048 bytes")
    }

    @Test
    fun `download uses downloadTrackUrls instead of trackUrls when set`() = runTest {
        val fileStore = TempFileStore()
        val requested = mutableListOf<String>()
        val repo = downloadRepo(
            fileStore,
            client = mockClient(record = requested),
            audiobookRepository = FakeAudiobookRepository(
                session(trackUrls = listOf("https://hls/stream.m3u8"), downloadTrackUrls = listOf("https://cdn/file.mp3")),
            ),
        )

        repo.download("s1", "i1") { _, _ -> }

        assertEquals(listOf("https://cdn/file.mp3"), requested)
    }

    @Test
    fun `download promotes a completed cache without opening a network session`() = runTest {
        val fileStore = TempFileStore()
        val cache = cacheRepo(fileStore)
        cache.awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1")))
        assertTrue(cache.isCached("s1", "i1"))

        val audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1")))
        val repo = downloadRepo(fileStore, audiobookRepository = audiobookRepository)

        assertEquals(AudiobookDownloadResult.Success, repo.download("s1", "i1") { _, _ -> })

        assertEquals(0, audiobookRepository.openSessionCalls, "promotion must not reopen a play session")
        assertTrue(repo.isDownloaded("s1", "i1"))
        assertFalse(cache.isCached("s1", "i1"), "cache copy should have moved, not been duplicated")
    }

    @Test
    fun `download notifies local availability changed`() = runTest {
        val fileStore = TempFileStore()
        val events = RecordingAvailabilityEvents()
        val repo = downloadRepo(
            fileStore,
            audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1"))),
            events = events,
        )

        repo.download("s1", "i1") { _, _ -> }

        assertEquals(listOf("s1" to "i1"), events.notified)
    }

    @Test
    fun `download fails without leaving a partial directory when no session can be opened`() = runTest {
        val fileStore = TempFileStore()
        val repo = downloadRepo(fileStore, audiobookRepository = FakeAudiobookRepository(null))

        val result = repo.download("s1", "i1") { _, _ -> }

        assertTrue(result is AudiobookDownloadResult.NetworkError, "expected NetworkError, got $result")
        assertFalse(repo.isDownloaded("s1", "i1"))
    }

    @Test
    fun `remove deletes the directory and reports freed bytes`() = runTest {
        val fileStore = TempFileStore()
        val repo = downloadRepo(
            fileStore,
            audiobookRepository = FakeAudiobookRepository(session(listOf("https://x/1", "https://x/2"))),
        )
        repo.download("s1", "i1") { _, _ -> }

        val freed = repo.remove("s1", "i1")

        assertTrue(freed >= 2048L, "expected at least the two 1 KiB tracks, got $freed")
        assertFalse(repo.isDownloaded("s1", "i1"))
        assertNull(repo.localSession("s1", "i1"))
    }

    // ── Cache repository ──────────────────────────────────────────────────────

    @Test
    fun `awaitCachedAudiobook downloads all tracks and writes the manifest`() = runTest {
        val fileStore = TempFileStore()
        val repo = cacheRepo(fileStore)

        repo.awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1", "https://x/2")))

        assertTrue(repo.isCached("s1", "i1"))
        val dir = "${fileStore.resolve(NS_AUDIOBOOK_CACHE)}/s1/i1"
        assertTrue(IosAudiobookFiles.exists("$dir/track-0"))
        assertTrue(IosAudiobookFiles.exists("$dir/track-1"))
    }

    @Test
    fun `awaitCachedAudiobook is a no-op when already cached`() = runTest {
        val fileStore = TempFileStore()
        val requested = mutableListOf<String>()
        val repo = cacheRepo(fileStore, client = mockClient(record = requested))
        repo.awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1")))
        val afterFirst = requested.size

        repo.awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1")))

        assertEquals(afterFirst, requested.size, "second call must not refetch")
    }

    @Test
    fun `awaitCachedAudiobook notifies local availability changed`() = runTest {
        val fileStore = TempFileStore()
        val events = RecordingAvailabilityEvents()
        cacheRepo(fileStore, events = events).awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1")))

        assertEquals(listOf("s1" to "i1"), events.notified)
    }

    @Test
    fun `awaitCachedAudiobook silently swallows failures and leaves no partial dir`() = runTest {
        val fileStore = TempFileStore()
        val failing = HttpClient(MockEngine { respond(content = ByteArray(0), status = HttpStatusCode.InternalServerError) })
        val repo = cacheRepo(fileStore, client = failing)

        repo.awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1")))

        assertFalse(repo.isCached("s1", "i1"))
        assertFalse(
            IosAudiobookFiles.exists("${fileStore.resolve(NS_AUDIOBOOK_CACHE)}/s1/i1"),
            "a failed cache attempt must not leave a partial directory",
        )
    }

    @Test
    fun `localSession returns null and deletes a stale zero-duration live-stream entry`() = runTest {
        val fileStore = TempFileStore()
        val repo = cacheRepo(fileStore)
        val live = AudiobookSession(
            trackUrls = listOf("https://x/live"),
            tracks = listOf(AudiobookTrackSpan(0, 0.0, 0.0)),
            timeline = AudiobookTimeline(durationSec = 0.0, chapters = emptyList()),
            serverCurrentTimeSec = 0.0,
        )
        repo.awaitCachedAudiobook("s1", "live", live)
        val dir = "${fileStore.resolve(NS_AUDIOBOOK_CACHE)}/s1/live"
        assertTrue(IosAudiobookFiles.exists(dir), "precondition: the live entry was cached")

        assertNull(repo.localSession("s1", "live"))
        assertFalse(IosAudiobookFiles.exists(dir), "the stale live-stream entry should be deleted")
    }

    @Test
    fun `cache remove deletes the directory and reports freed bytes`() = runTest {
        val fileStore = TempFileStore()
        val repo = cacheRepo(fileStore)
        repo.awaitCachedAudiobook("s1", "i1", session(listOf("https://x/1")))

        val freed = repo.remove("s1", "i1")

        assertTrue(freed >= 1024L, "expected at least the 1 KiB track, got $freed")
        assertFalse(repo.isCached("s1", "i1"))
    }

    @Test
    fun `cache awaitCachedAudiobook uses downloadTrackUrls when set`() = runTest {
        val fileStore = TempFileStore()
        val requested = mutableListOf<String>()
        val repo = cacheRepo(fileStore, client = mockClient(record = requested))

        repo.awaitCachedAudiobook(
            "s1",
            "i1",
            session(trackUrls = listOf("https://hls/stream.m3u8"), downloadTrackUrls = listOf("https://cdn/file.mp3")),
        )

        assertEquals(listOf("https://cdn/file.mp3"), requested)
    }
}
