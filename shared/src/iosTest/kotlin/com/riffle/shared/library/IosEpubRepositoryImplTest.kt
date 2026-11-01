package com.riffle.shared.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.common.FileStore
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosEpubRepositoryImplTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        val root = NSTemporaryDirectory() + "epub_repo_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(
                base,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }

        override fun exists(namespace: String, relativePath: String): Boolean =
            NSFileManager.defaultManager.fileExistsAtPath(resolve(namespace, relativePath))
    }

    private object NoOpPositionStore : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) {}
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private val epubBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4)

    private val item = LibraryItem(
        id = "item-1",
        sourceId = "source-1",
        libraryId = "lib-1",
        title = "Test Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = null,
    )

    private abstract class FakeCatalog : Catalog {
        override val sourceType: SourceType = SourceType.ABS
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(
            rootId: String,
            sort: SortKey,
            page: Int,
            pageSize: Int,
            facet: FacetSelection?,
        ): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not used")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = false)
    }

    private inner class SpyCatalog(private val bytes: ByteArray) : FakeCatalog() {
        var callCount = 0
        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T {
            callCount++
            return block(object : CatalogFileStream {
                override val contentLength: Long = bytes.size.toLong()
                override val channel: ByteReadChannel = ByteReadChannel(bytes)
            })
        }
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }

    private fun registry(catalog: Catalog?): CatalogRegistry = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private fun repo(store: TempFileStore, catalog: Catalog?): IosEpubRepositoryImpl =
        IosEpubRepositoryImpl(NoOpPositionStore, store, registry(catalog))

    private fun writeCacheFile(store: TempFileStore, bytes: ByteArray): String {
        val path = store.resolve(
            NS_EPUB_CACHE,
            IosEpubPaths.cacheRelativePath(item.sourceId, item.id),
        )
        NSFileManager.defaultManager.createDirectoryAtPath(
            path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }.writeToFile(path, atomically = true)
        return path
    }

    @Test
    fun downloadEpubPromotesCachedFileToDownloadsWithoutNetworkRequest() = runTest {
        val store = TempFileStore()
        val catalog = SpyCatalog(epubBytes)
        val cachePath = writeCacheFile(store, epubBytes)
        val r = repo(store, catalog)

        val result = r.downloadEpub(item) { _, _ -> }

        assertTrue(result is EpubDownloadResult.Success, "expected Success, got $result")
        assertEquals(0, catalog.callCount, "cache promotion must not issue any network request")
        assertTrue(r.isDownloaded(item.sourceId, item.id), "file must be in downloads after promotion")
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(cachePath), "cached file must be moved (not copied) to downloads")
    }

    @Test
    fun downloadEpubReturnsAlreadyDownloadedWhenFileIsInDownloadsStore() = runTest {
        val store = TempFileStore()
        val catalog = SpyCatalog(epubBytes)
        val r = repo(store, catalog)

        r.downloadEpub(item) { _, _ -> }
        val result = r.downloadEpub(item) { _, _ -> }

        assertTrue(result is EpubDownloadResult.AlreadyDownloaded, "expected AlreadyDownloaded, got $result")
        assertEquals(1, catalog.callCount, "second downloadEpub must not issue another network request")
    }

    @Test
    fun downloadEpubFetchesFromNetworkWhenNeitherDownloadedNorCached() = runTest {
        val store = TempFileStore()
        val catalog = SpyCatalog(epubBytes)

        val result = repo(store, catalog).downloadEpub(item) { _, _ -> }

        assertTrue(result is EpubDownloadResult.Success, "expected Success, got $result")
        assertEquals(1, catalog.callCount, "a network download must be issued on a cache miss")
    }
}
