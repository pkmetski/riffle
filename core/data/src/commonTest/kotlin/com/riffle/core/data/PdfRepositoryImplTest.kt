package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.common.FileStore
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfRepositoryImplTest {

    private val testRoots = mutableListOf<okio.Path>()

    @AfterTest
    fun cleanUp() {
        testRoots.forEach { root ->
            runCatching { FileSystem.SYSTEM.deleteRecursively(root) }
        }
    }

    private fun tempRoot(): String {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pdf_repo_test_${Random.nextLong()}"
        FileSystem.SYSTEM.createDirectories(root)
        testRoots += root
        return root.toString()
    }

    private class FakeFileStore(private val root: String) : FileStore {
        override fun resolve(namespace: String, relativePath: String): String =
            if (relativePath.isEmpty()) "$root/$namespace" else "$root/$namespace/$relativePath"

        override fun exists(namespace: String, relativePath: String): Boolean =
            FileSystem.SYSTEM.exists(resolve(namespace, relativePath).toPath())
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

    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 1, 2, 3, 4)

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
        ebookFormat = EbookFormat.Pdf,
        ebookFileIno = null,
    )

    private fun repo(catalog: Catalog?, root: String): PdfRepositoryImpl =
        PdfRepositoryImpl(
            fileStore = FakeFileStore(root),
            catalogRegistry = registry(catalog),
            positionStore = NoOpPositionStore,
        )

    @Test
    fun downloadPdfFetchesFromNetworkWhenNeitherDownloadedNorCached() = runTest {
        val catalog = SpyCatalog(pdfBytes)
        val root = tempRoot()

        val result = repo(catalog, root).downloadPdf(item) { _, _ -> }

        assertTrue(result is PdfDownloadResult.Success, "expected Success, got $result")
        assertEquals(1, catalog.callCount, "a network download must be issued on a cache miss")
    }

    @Test
    fun downloadPdfReturnsAlreadyDownloadedWhenFileIsInDownloadsStore() = runTest {
        val catalog = SpyCatalog(pdfBytes)
        val root = tempRoot()
        val r = repo(catalog, root)

        r.downloadPdf(item) { _, _ -> }
        val result = r.downloadPdf(item) { _, _ -> }

        assertTrue(result is PdfDownloadResult.AlreadyDownloaded, "expected AlreadyDownloaded, got $result")
        assertEquals(1, catalog.callCount, "second downloadPdf must not issue another network request")
    }

    @Test
    fun downloadPdfPromotesCachedFileToDownloadsWithoutNetworkRequest() = runTest {
        val catalog = SpyCatalog(pdfBytes)
        val root = tempRoot()
        val r = repo(catalog, root)
        val cachePath = "$root/$NS_PDF_CACHE/source-1/item-1.pdf".toPath()
        FileSystem.SYSTEM.createDirectories(cachePath.parent!!)
        FileSystem.SYSTEM.write(cachePath) { write(pdfBytes) }

        val result = r.downloadPdf(item) { _, _ -> }

        assertTrue(result is PdfDownloadResult.Success, "expected Success, got $result")
        assertEquals(0, catalog.callCount, "cache promotion must not issue any network request")
        assertTrue(r.isDownloaded(item.sourceId, item.id), "file must be in downloads after promotion")
        assertFalse(FileSystem.SYSTEM.exists(cachePath), "cached file must be moved from cache")
    }

    @Test
    fun isDownloadedReturnsFalseWhenFileAbsent() {
        assertFalse(repo(null, tempRoot()).isDownloaded(item.sourceId, item.id))
    }

    @Test
    fun isCachedReturnsFalseWhenFileAbsent() {
        assertFalse(repo(null, tempRoot()).isCached(item.sourceId, item.id))
    }

    @Test
    fun removeDownloadDeletesDownloadFile() = runTest {
        val catalog = SpyCatalog(pdfBytes)
        val root = tempRoot()
        val r = repo(catalog, root)
        r.downloadPdf(item) { _, _ -> }
        assertTrue(r.isDownloaded(item.sourceId, item.id))

        r.removeDownload(item.sourceId, item.id)

        assertFalse(r.isDownloaded(item.sourceId, item.id), "download file must be deleted")
    }

    @Test
    fun downloadPdfReturnsNetworkErrorWhenNoCatalogIsRegistered() = runTest {
        val result = repo(null, tempRoot()).downloadPdf(item) { _, _ -> }

        assertTrue(result is PdfDownloadResult.NetworkError, "expected NetworkError when no catalog, got $result")
    }
}
