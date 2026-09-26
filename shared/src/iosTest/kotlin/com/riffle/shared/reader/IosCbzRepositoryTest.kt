package com.riffle.shared.reader

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.KomgaCbzApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS CBZ download/cache stores (#1101). Before this change `awaitCachedSource` was `= null`,
 * `isCached`/`isDownloaded` were `false` and `downloadCbz` answered NetworkError without
 * fetching anything, so the CBZ reader streamed forever and nothing could be read offline. Every
 * test drives the real repository over a temp-dir [FileStore] and Ktor's MockEngine.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCbzRepositoryTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "cbz_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private val source = Source(
        id = "src-1",
        url = SourceUrl.parse("https://abs.example.test/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "reader",
    )

    private val sourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOf(source))
        override suspend fun getActive(): Source? = source
        override suspend fun getById(sourceId: String): Source? = source.takeIf { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val tokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = "tok"
        override suspend fun deleteToken(sourceId: String) {}
    }

    private val noopCbzApi = object : KomgaCbzApi {
        override suspend fun fetchCbzPageCount(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): Int = 0
        override suspend fun fetchCbzPage(
            baseUrl: String,
            bookId: String,
            pageIndex: Int,
            maxWidth: Int?,
            token: String,
            insecureAllowed: Boolean,
        ): ByteArray = byteArrayOf()
    }

    private val noopPositionStore = object : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) {}
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private val noCatalogs = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = null
        override suspend fun forSource(source: Source): Catalog? = null
        override suspend fun forSourceId(sourceId: String): Catalog? = null
    }

    private class RecordingAccessStore : ContentCacheAccessStore {
        val marked = mutableListOf<ContentCacheKey>()
        override suspend fun markAccessed(key: ContentCacheKey) {
            marked += key
        }
        override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) {
            marked += key
        }
        override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = null
        override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> = emptyMap()
        override suspend fun forget(key: ContentCacheKey) {}
    }

    private class RecordingAvailabilityEvents : LocalAvailabilityEvents {
        val notified = mutableListOf<Pair<String, String>>()
        private val _changes = MutableSharedFlow<StoredItemRef>(extraBufferCapacity = 8)
        override val changes: SharedFlow<StoredItemRef> get() = _changes
        override fun notifyChanged(sourceId: String, itemId: String) {
            notified += sourceId to itemId
        }
    }

    private val item = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Test CBZ Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Cbz,
        ebookFileIno = "ino-1",
        hasAudio = false,
        audioDurationSec = 0.0,
        description = null,
        seriesName = null,
        publishedYear = null,
        genres = emptyList(),
        publisher = null,
        language = null,
        lastOpenedAt = null,
        addedAt = null,
        isbn = null,
        sourceId = "src-1",
    )

    private val archiveBytes = storedZip("001.png" to byteArrayOf(1, 2, 3, 4), "002.png" to byteArrayOf(5, 6))

    private fun servingClient(requests: MutableList<String> = mutableListOf()) = HttpClient(
        MockEngine { request ->
            requests += request.url.toString()
            respond(
                content = archiveBytes,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Length", archiveBytes.size.toString()),
            )
        },
    )

    private fun failingClient() = HttpClient(MockEngine { respond(content = ByteArray(0), status = HttpStatusCode.InternalServerError) })

    private fun repo(
        client: HttpClient,
        fileStore: FileStore,
        access: RecordingAccessStore = RecordingAccessStore(),
        events: RecordingAvailabilityEvents = RecordingAvailabilityEvents(),
    ) = IosCbzRepository(
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        cbzApi = noopCbzApi,
        downloader = IosCbzDownloader(client, sourceRepository, tokenStorage),
        positionStore = noopPositionStore,
        fileStore = fileStore,
        catalogRegistry = noCatalogs,
        contentCacheAccessStore = access,
        localAvailabilityEvents = events,
    )

    @Test
    fun awaitCachedSourceDownloadsTheArchiveIntoTheCacheAndOpensIt() = runTest {
        val store = TempFileStore()
        val requests = mutableListOf<String>()
        val access = RecordingAccessStore()
        val events = RecordingAvailabilityEvents()
        val repo = repo(servingClient(requests), store, access, events)

        val local = repo.awaitCachedSource(item)

        assertNotNull(local, "a successful download must yield a local source")
        assertEquals(2, local.pageCount)
        assertEquals(listOf(1, 2, 3, 4).map { it.toByte() }.toByteArray().toList(), local.imageSource.imageBytes(0).toList())
        assertEquals(listOf("https://abs.example.test/api/items/item-1/file/ino-1"), requests)
        assertTrue(repo.isCached("src-1", "item-1"), "the archive is now in the cache store")
        assertFalse(repo.isDownloaded("src-1", "item-1"), "a background cache fill is not a user download")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(store.resolve("cbz-cache", "src-1/item-1.cbz")))
        assertEquals(listOf("src-1" to "item-1"), events.notified)
        assertEquals(1, access.marked.size)
    }

    @Test
    fun awaitCachedSourceAndOpenCbzServeTheCachedArchiveWithoutTheNetwork() = runTest {
        val store = TempFileStore()
        repo(servingClient(), store).awaitCachedSource(item)

        // A second repository whose HTTP client fails every request: only the cache can answer.
        val offline = repo(failingClient(), store)
        val local = offline.awaitCachedSource(item)
        assertNotNull(local)
        assertEquals(2, local.pageCount)

        val opened = offline.openCbz(item)
        assertIs<CbzOpenResult.Success>(opened, "openCbz must open the cached archive instead of streaming")
        assertEquals(2, opened.pageCount)
    }

    @Test
    fun awaitCachedSourceReturnsNullAndLeavesNoFileWhenTheDownloadFails() = runTest {
        val store = TempFileStore()
        val repo = repo(failingClient(), store)

        assertNull(repo.awaitCachedSource(item))
        assertFalse(repo.isCached("src-1", "item-1"))
    }

    @Test
    fun aCorruptCachedFileIsDiscardedAndReplacedFromTheNetwork() = runTest {
        val store = TempFileStore()
        val serving = repo(servingClient(), store)
        val path = store.resolve("cbz-cache", "src-1/item-1.cbz")
        IosCbzFiles(store).writeBytes(path, byteArrayOf(0, 1, 2))

        val local = serving.awaitCachedSource(item)

        assertNotNull(local)
        assertEquals(2, local.pageCount)
        assertEquals(archiveBytes.toList(), IosCbzFiles(store).readBytes(path)!!.toList(), "the truncated file was replaced")
    }

    @Test
    fun downloadCbzWritesThePinnedDownloadAndReportsAlreadyDownloadedOnRepeat() = runTest {
        val store = TempFileStore()
        val events = RecordingAvailabilityEvents()
        val repo = repo(servingClient(), store, events = events)
        var progress: Pair<Long, Long>? = null

        val result = repo.downloadCbz(item) { d, t -> progress = d to t }

        assertIs<CbzDownloadResult.Success>(result)
        assertTrue(repo.isDownloaded("src-1", "item-1"))
        assertEquals(archiveBytes.size.toLong() to archiveBytes.size.toLong(), progress)
        assertEquals(listOf("src-1" to "item-1"), events.notified)
        assertIs<CbzDownloadResult.AlreadyDownloaded>(repo.downloadCbz(item) { _, _ -> })
        assertIs<CbzOpenResult.Success>(repo(failingClient(), store).openCbz(item), "downloaded archive opens offline")
    }

    @Test
    fun downloadCbzReturnsNetworkErrorWhenTheDownloadFails() = runTest {
        val store = TempFileStore()
        val result = repo(failingClient(), store).downloadCbz(item) { _, _ -> }

        assertIs<CbzDownloadResult.NetworkError>(result, "Success must never be reported without bytes on disk")
        assertFalse(repo(failingClient(), store).isDownloaded("src-1", "item-1"))
    }

    @Test
    fun removeDownloadDeletesBothTiers() = runTest {
        val store = TempFileStore()
        val repo = repo(servingClient(), store)
        repo.downloadCbz(item) { _, _ -> }
        repo.awaitCachedSource(item)

        repo.removeDownload("src-1", "item-1")

        assertFalse(repo.isDownloaded("src-1", "item-1"))
        assertFalse(repo.isCached("src-1", "item-1"))
    }

    // --- A minimal STORED (method 0) ZIP writer; IosCbzArchive does not verify CRCs. ---

    private class ZipBytes {
        val bytes = ArrayList<Byte>()
        val size: Int get() = bytes.size

        fun u16(v: Int) {
            bytes.add((v and 0xFF).toByte())
            bytes.add(((v shr 8) and 0xFF).toByte())
        }

        fun u32(v: Int) {
            u16(v and 0xFFFF)
            u16((v ushr 16) and 0xFFFF)
        }

        fun raw(data: ByteArray) {
            data.forEach { bytes.add(it) }
        }

        fun append(other: ZipBytes) {
            bytes.addAll(other.bytes)
        }
    }

    private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ZipBytes()
        val central = ZipBytes()
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val offset = out.size
            // Local file header: signature, version, flags, method, time, date, crc, sizes, name/extra lengths.
            out.u32(0x04034B50)
            listOf(20, 0, 0, 0, 0).forEach { out.u16(it) }
            out.u32(0)
            out.u32(data.size)
            out.u32(data.size)
            out.u16(nameBytes.size)
            out.u16(0)
            out.raw(nameBytes)
            out.raw(data)
            // Central directory entry.
            central.u32(0x02014B50)
            listOf(20, 20, 0, 0, 0, 0).forEach { central.u16(it) }
            central.u32(0)
            central.u32(data.size)
            central.u32(data.size)
            listOf(nameBytes.size, 0, 0, 0, 0).forEach { central.u16(it) }
            central.u32(0)
            central.u32(offset)
            central.raw(nameBytes)
        }
        val cdOffset = out.size
        out.append(central)
        // End of central directory.
        out.u32(0x06054B50)
        listOf(0, 0, entries.size, entries.size).forEach { out.u16(it) }
        out.u32(central.size)
        out.u32(cdOffset)
        out.u16(0)
        return out.bytes.toByteArray()
    }
}
