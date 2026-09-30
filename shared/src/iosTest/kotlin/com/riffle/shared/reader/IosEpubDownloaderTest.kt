package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
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
 * Key claim: localPath() returns the written cache path for any source (ABS, Komga, …) by going
 * through CatalogRegistry.withFileStream rather than ABS-specific HTTP calls. Previously it used
 * NSFileManager.createFileAtPath() without first creating the $sourceId/ parent directory, so the
 * write silently returned false and localPath() returned null for every book on every source,
 * showing "Could not download book".
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

    private val item = LibraryItem(
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
        ebookFileIno = "ino-1",
    )

    private fun catalog(bytes: ByteArray): Catalog = object : Catalog {
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

    private fun failingCatalog(): Catalog = object : Catalog {
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
    fun localPathCreatesTheSourceIdSubdirectoryAndReturnsTheCachePath() = runTest {
        val store = TempFileStore()
        val path = IosEpubDownloader(registry(catalog(epubBytes)), store).localPath(item)

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
    fun localPathReturnsNullWhenTheCatalogThrows() = runTest {
        val path = IosEpubDownloader(registry(failingCatalog()), TempFileStore()).localPath(item)
        assertNull(path, "a catalog error must yield null so the reader shows the error UI")
    }

    @Test
    fun localPathReturnsNullWhenNoCatalogIsRegisteredForTheSource() = runTest {
        val path = IosEpubDownloader(registry(null), TempFileStore()).localPath(item)
        assertNull(path, "no catalog for source must yield null")
    }

    @Test
    fun localPathReturnsCacheHitWithoutHittingTheCatalogASecondTime() = runTest {
        val store = TempFileStore()
        var calls = 0
        val countingCatalog = object : Catalog {
            override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T {
                calls++
                return block(object : CatalogFileStream {
                    override val contentLength: Long = epubBytes.size.toLong()
                    override val channel: ByteReadChannel = ByteReadChannel(epubBytes)
                })
            }
            override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        }
        val downloader = IosEpubDownloader(registry(countingCatalog), store)

        val first = downloader.localPath(item)
        assertNotNull(first, "first call must download and cache")

        val second = downloader.localPath(item)
        assertNotNull(second, "second call must return the cached path")
        assertTrue(calls == 1, "catalog must only be called once; was called $calls times")
    }
}
