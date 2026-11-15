package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.common.FileStore
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS CBZ download/cache/open behaviour via [IosCbzRepositoryImpl]. Exercises the shared base
 * class ([com.riffle.core.data.CbzRepositoryImpl]) on the iOS code path using a [FakeFileSystem].
 */
class IosCbzRepositoryTest {

    private val fakeFs = FakeFileSystem()

    @AfterTest
    fun cleanUp() {
        fakeFs.checkNoOpenFiles()
    }

    private inner class FakeFsFileStore : FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = "/riffle/$namespace"
            fakeFs.createDirectories(base.toPath())
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }

        override fun exists(namespace: String, relativePath: String): Boolean =
            fakeFs.exists(resolve(namespace, relativePath).toPath())
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

    private class RecordingAccessStore : ContentCacheAccessStore {
        val marked = mutableListOf<ContentCacheKey>()
        override suspend fun markAccessed(key: ContentCacheKey) { marked += key }
        override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) { marked += key }
        override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = null
        override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> = emptyMap()
        override suspend fun forget(key: ContentCacheKey) {}
    }

    private class RecordingAvailabilityEvents : LocalAvailabilityEvents {
        val notified = mutableListOf<Pair<String, String>>()
        private val _changes = MutableSharedFlow<StoredItemRef>(extraBufferCapacity = 8)
        override val changes: SharedFlow<StoredItemRef> get() = _changes
        override fun notifyChanged(sourceId: String, itemId: String) { notified += sourceId to itemId }
    }

    private abstract class StubCatalog : Catalog {
        override val sourceType: SourceType get() = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: com.riffle.core.catalog.SortKey, page: Int, pageSize: Int, facet: com.riffle.core.catalog.FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not implemented")
    }

    private fun servingCatalog(bytes: ByteArray): Catalog = object : StubCatalog() {
        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = block(object : CatalogFileStream {
            override val contentLength: Long get() = bytes.size.toLong()
            override val channel: ByteReadChannel get() = ByteReadChannel(bytes)
        })
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }

    private fun failingCatalog(): Catalog = object : StubCatalog() {
        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = throw RuntimeException("network unavailable")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = false)
    }

    private fun catalogRegistryFor(catalog: Catalog?) = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private val item = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "CBZ Test Book",
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

    private fun repo(
        catalog: Catalog?,
        fileStore: FileStore = FakeFsFileStore(),
        access: RecordingAccessStore = RecordingAccessStore(),
        events: RecordingAvailabilityEvents = RecordingAvailabilityEvents(),
    ) = IosCbzRepositoryImpl(
        fileStore = fileStore,
        catalogRegistry = catalogRegistryFor(catalog),
        positionStore = noopPositionStore,
        contentCacheAccessStore = access,
        localAvailabilityEvents = events,
        fileSystem = fakeFs,
        dispatchers = IosDispatcherProvider,
    )

    @Test
    fun awaitCachedSourceDownloadsTheArchiveIntoTheCacheAndOpensIt() = runTest {
        val store = FakeFsFileStore()
        val access = RecordingAccessStore()
        val events = RecordingAvailabilityEvents()
        val repo = repo(servingCatalog(archiveBytes), store, access, events)

        val local = repo.awaitCachedSource(item)

        assertNotNull(local, "a successful download must yield a local source")
        assertEquals(2, local.pageCount)
        assertEquals(listOf(1, 2, 3, 4).map { it.toByte() }.toByteArray().toList(), local.imageSource.imageBytes(0).toList())
        assertTrue(repo.isCached("src-1", "item-1"), "the archive is now in the cache store")
        assertFalse(repo.isDownloaded("src-1", "item-1"), "a background cache fill is not a user download")
        assertTrue(fakeFs.exists(store.resolve("cbz-cache", "src-1/item-1.cbz").toPath()))
        assertEquals(listOf("src-1" to "item-1"), events.notified)
        assertEquals(1, access.marked.size)
    }

    @Test
    fun awaitCachedSourceAndOpenCbzServeTheCachedArchiveWithoutTheNetwork() = runTest {
        val store = FakeFsFileStore()
        repo(servingCatalog(archiveBytes), store).awaitCachedSource(item)

        val offline = repo(failingCatalog(), store)
        val local = offline.awaitCachedSource(item)
        assertNotNull(local)
        assertEquals(2, local.pageCount)

        val opened = offline.openCbz(item)
        assertIs<CbzOpenResult.Success>(opened, "openCbz must open the cached archive instead of network-streaming")
        assertEquals(2, opened.pageCount)
    }

    @Test
    fun awaitCachedSourceReturnsNullAndLeavesNoFileWhenTheDownloadFails() = runTest {
        val store = FakeFsFileStore()
        val repo = repo(failingCatalog(), store)

        assertNull(repo.awaitCachedSource(item))
        assertFalse(repo.isCached("src-1", "item-1"))
    }

    @Test
    fun aCorruptCachedFileIsDiscardedAndReplacedFromTheNetwork() = runTest {
        val store = FakeFsFileStore()
        val path = store.resolve("cbz-cache", "src-1/item-1.cbz").toPath()
        fakeFs.createDirectories(path.parent!!)
        fakeFs.write(path) { write(byteArrayOf(0, 1, 2)) }

        val local = repo(servingCatalog(archiveBytes), store).awaitCachedSource(item)

        assertNotNull(local)
        assertEquals(2, local.pageCount)
        assertEquals(archiveBytes.toList(), fakeFs.read(path) { readByteArray() }.toList(), "the truncated file was replaced")
    }

    @Test
    fun downloadCbzWritesThePinnedDownloadAndReportsAlreadyDownloadedOnRepeat() = runTest {
        val store = FakeFsFileStore()
        val events = RecordingAvailabilityEvents()
        val repo = repo(servingCatalog(archiveBytes), store, events = events)
        var progress: Pair<Long, Long>? = null

        val result = repo.downloadCbz(item) { d, t -> progress = d to t }

        assertIs<CbzDownloadResult.Success>(result)
        assertTrue(repo.isDownloaded("src-1", "item-1"))
        assertEquals(archiveBytes.size.toLong() to archiveBytes.size.toLong(), progress)
        assertEquals(listOf("src-1" to "item-1"), events.notified)
        assertIs<CbzDownloadResult.AlreadyDownloaded>(repo.downloadCbz(item) { _, _ -> })
        assertIs<CbzOpenResult.Success>(repo(failingCatalog(), store).openCbz(item), "downloaded archive opens offline")
    }

    @Test
    fun downloadCbzReturnsNetworkErrorWhenTheDownloadFails() = runTest {
        val store = FakeFsFileStore()
        val result = repo(failingCatalog(), store).downloadCbz(item) { _, _ -> }

        assertIs<CbzDownloadResult.NetworkError>(result, "Success must never be reported without bytes on disk")
        assertFalse(repo(failingCatalog(), store).isDownloaded("src-1", "item-1"))
    }

    @Test
    fun removeDownloadDeletesBothTiers() = runTest {
        val store = FakeFsFileStore()
        val repo = repo(servingCatalog(archiveBytes), store)
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
            out.u32(0x04034B50)
            listOf(20, 0, 0, 0, 0).forEach { out.u16(it) }
            out.u32(0)
            out.u32(data.size)
            out.u32(data.size)
            out.u16(nameBytes.size)
            out.u16(0)
            out.raw(nameBytes)
            out.raw(data)
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
        out.u32(0x06054B50)
        listOf(0, 0, entries.size, entries.size).forEach { out.u16(it) }
        out.u32(central.size)
        out.u32(cdOffset)
        out.u16(0)
        return out.bytes.toByteArray()
    }
}
