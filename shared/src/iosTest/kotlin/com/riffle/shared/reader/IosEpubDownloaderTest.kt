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
 * Regression tests for IosEpubDownloader.
 *
 * Non-ABS items (ebookFileIno == null) go through CatalogRegistry → Catalog.withFileStream.
 * ABS items (ebookFileIno != null) go through AbsFileStreamer, bypassing AbsCommonCatalog which
 * throws UnsupportedOperation for withFileStream (the bug that prompted the two-path split).
 *
 * Both paths write via IosItemFiles.writeChannel which calls mkdirsForFile() first, so the
 * $sourceId/ subdirectory is always created before the write — the original "Could not download
 * book" bug.
 */
@OptIn(ExperimentalForeignApi::class)
class IosEpubDownloaderTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        val root = NSTemporaryDirectory() + "epub_dl_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private val epubBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4)

    /** Non-ABS item: ebookFileIno is null, so the catalog path is taken. */
    private val catalogItem = LibraryItem(
        id = "item-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "Test EPUB Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = null,
    )

    /** ABS item: ebookFileIno is set, so the AbsFileStreamer path is taken. */
    private val absItem = LibraryItem(
        id = "item-2",
        sourceId = "src-abs",
        libraryId = "lib-abs",
        title = "ABS EPUB Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = "ino-1",
    )

    /** Minimal Catalog stub — only withFileStream and connectivityCheck are exercised by this test. */
    private abstract class FakeCatalog : Catalog {
        override val sourceType: SourceType = SourceType.ABS
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: com.riffle.core.catalog.SortKey, page: Int, pageSize: Int, facet: com.riffle.core.catalog.FacetSelection?): List<CatalogItem> = emptyList()
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

    private fun absStreamer(bytes: ByteArray): AbsFileStreamer = object : AbsFileStreamer {
        override suspend fun <T> withStream(item: LibraryItem, sink: suspend (channel: ByteReadChannel, contentLength: Long) -> T): T =
            sink(ByteReadChannel(bytes), bytes.size.toLong())
    }

    private fun failingAbsStreamer(): AbsFileStreamer = object : AbsFileStreamer {
        override suspend fun <T> withStream(item: LibraryItem, sink: suspend (channel: ByteReadChannel, contentLength: Long) -> T): T? = null
    }

    private val noOpAbsStreamer: AbsFileStreamer = object : AbsFileStreamer {
        override suspend fun <T> withStream(item: LibraryItem, sink: suspend (channel: ByteReadChannel, contentLength: Long) -> T): T? =
            error("AbsFileStreamer must not be called for non-ABS items")
    }

    private val noOpRegistry = registry(null)

    @Test
    fun catalogItemCreatesTheSourceIdSubdirectoryAndReturnsTheCachePath() = runTest {
        val store = TempFileStore()
        val path = IosEpubDownloader(noOpAbsStreamer, registry(catalog(epubBytes)), store).localPath(catalogItem)

        assertNotNull(path, "localPath must return a non-null path when the catalog delivers bytes")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(path),
            "the file must exist at the returned path",
        )
        assertTrue(
            path.endsWith("src-1/item-1.epub"),
            "path must include the sourceId/ subdirectory: was $path",
        )
    }

    @Test
    fun catalogItemReturnsNullWhenTheCatalogThrows() = runTest {
        val path = IosEpubDownloader(noOpAbsStreamer, registry(failingCatalog()), TempFileStore()).localPath(catalogItem)
        assertNull(path, "a catalog error must yield null so the reader shows the error UI")
    }

    @Test
    fun catalogItemReturnsNullWhenNoCatalogIsRegisteredForTheSource() = runTest {
        val path = IosEpubDownloader(noOpAbsStreamer, registry(null), TempFileStore()).localPath(catalogItem)
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
                    override val contentLength: Long = epubBytes.size.toLong()
                    override val channel: ByteReadChannel = ByteReadChannel(epubBytes)
                })
            }
            override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        }
        val downloader = IosEpubDownloader(noOpAbsStreamer, registry(countingCatalog), store)

        val first = downloader.localPath(catalogItem)
        assertNotNull(first, "first call must download and cache")

        val second = downloader.localPath(catalogItem)
        assertNotNull(second, "second call must return the cached path")
        assertTrue(calls == 1, "catalog must only be called once; was called $calls times")
    }

    /**
     * ABS items (ebookFileIno != null) must bypass AbsCommonCatalog (which throws
     * UnsupportedOperation) and stream directly through AbsFileStreamer.
     */
    @Test
    fun absItemDownloadsViaAbsStreamerAndCreatesSourceIdSubdirectory() = runTest {
        val store = TempFileStore()
        val path = IosEpubDownloader(absStreamer(epubBytes), noOpRegistry, store).localPath(absItem)

        assertNotNull(path, "ABS item must return a non-null path when AbsFileStreamer delivers bytes")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(path),
            "the file must exist at the returned path",
        )
        assertTrue(
            path.endsWith("src-abs/item-2.epub"),
            "path must include the sourceId/ subdirectory: was $path",
        )
    }

    @Test
    fun absItemReturnsNullWhenAbsStreamerFails() = runTest {
        val path = IosEpubDownloader(failingAbsStreamer(), noOpRegistry, TempFileStore()).localPath(absItem)
        assertNull(path, "an AbsFileStreamer failure must yield null so the reader shows the error UI")
    }
}
