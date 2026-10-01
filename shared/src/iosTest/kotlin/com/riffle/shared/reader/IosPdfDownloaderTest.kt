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
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [IosPdfDownloader], mirroring [IosEpubDownloaderTest].
 *
 * Verifies that PDF files are routed through [CatalogRegistry] → [Catalog.withFileStream]
 * for every source type (not just ABS), and that [IosItemFiles.writeChannel] creates the
 * $sourceId/ subdirectory so writes don't silently fail.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPdfDownloaderTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        val root = NSTemporaryDirectory() + "pdf_dl_test_" + NSUUID().UUIDString()
        init {
            roots += root
        }
        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 1, 2, 3, 4) // %PDF magic

    private val catalogItem = LibraryItem(
        id = "item-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "Test PDF",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Pdf,
        ebookFileIno = null,
    )

    private val absItem = LibraryItem(
        id = "item-2",
        sourceId = "src-abs",
        libraryId = "lib-abs",
        title = "ABS PDF",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Pdf,
        ebookFileIno = null,
    )

    private abstract class FakeCatalog : Catalog {
        override val sourceType: SourceType = SourceType.ABS
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(
            rootId: String,
            sort: com.riffle.core.catalog.SortKey,
            page: Int,
            pageSize: Int,
            facet: com.riffle.core.catalog.FacetSelection?,
        ): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not used in tests")
    }

    private fun catalog(bytes: ByteArray): Catalog = object : FakeCatalog() {
        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = block(object : CatalogFileStream {
            override val contentLength: Long = bytes.size.toLong()
            override val channel: ByteReadChannel = ByteReadChannel(bytes)
        })
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }

    private fun failingCatalog(): Catalog = object : FakeCatalog() {
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T =
            throw IllegalStateException("simulated network error")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = false)
    }

    private fun registry(catalog: Catalog?) = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    @Test
    fun catalogItemCreatesTheSourceIdSubdirectoryAndReturnsTheCachePath() = runTest {
        val store = TempFileStore()
        val path = IosPdfDownloader(registry(catalog(pdfBytes)), store).localPath(catalogItem)

        assertNotNull(path, "localPath must return a non-null path when the catalog delivers bytes")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(path),
            "the file must exist at the returned path",
        )
        assertTrue(
            path.endsWith("src-1/item-1.pdf"),
            "path must include the sourceId/ subdirectory: was $path",
        )
    }

    @Test
    fun catalogItemReturnsNullWhenTheCatalogThrows() = runTest {
        val path = IosPdfDownloader(registry(failingCatalog()), TempFileStore()).localPath(catalogItem)
        assertNull(path, "a catalog error must yield null so the reader shows the error UI")
    }

    @Test
    fun catalogItemReturnsNullWhenNoCatalogIsRegisteredForTheSource() = runTest {
        val path = IosPdfDownloader(registry(null), TempFileStore()).localPath(catalogItem)
        assertNull(path, "no catalog for source must yield null")
    }

    @Test
    fun catalogItemReturnsCacheHitWithoutHittingTheCatalogASecondTime() = runTest {
        val store = TempFileStore()
        var calls = 0
        val countingCatalog = object : FakeCatalog() {
            override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T {
                calls++
                return block(object : CatalogFileStream {
                    override val contentLength: Long = pdfBytes.size.toLong()
                    override val channel: ByteReadChannel = ByteReadChannel(pdfBytes)
                })
            }
            override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        }
        val downloader = IosPdfDownloader(registry(countingCatalog), store)

        val first = downloader.localPath(catalogItem)
        assertNotNull(first, "first call must download and cache")

        val second = downloader.localPath(catalogItem)
        assertNotNull(second, "second call must return the cached path")
        assertTrue(calls == 1, "catalog must only be called once; was called $calls times")
    }

    @Test
    fun absItemDownloadsViaCatalogAndCreatesSourceIdSubdirectory() = runTest {
        val store = TempFileStore()
        val path = IosPdfDownloader(registry(catalog(pdfBytes)), store).localPath(absItem)

        assertNotNull(path, "ABS item must return a non-null path when the catalog delivers bytes")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(path),
            "the file must exist at the returned path",
        )
        assertTrue(
            path.endsWith("src-abs/item-2.pdf"),
            "path must include the sourceId/ subdirectory: was $path",
        )
    }

    @Test
    fun absItemReturnsNullWhenCatalogThrows() = runTest {
        val path = IosPdfDownloader(registry(failingCatalog()), TempFileStore()).localPath(absItem)
        assertNull(path, "a catalog error on an ABS item must yield null so the reader shows the error UI")
    }
}
