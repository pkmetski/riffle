package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookDownloadRepositoryStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
    private val catalog = StreamingOnlyCatalog(payload)
    private val registry = InlineCatalogRegistry(catalog)
    private val positionStore = EmptyPositionStore()

    @Test
    fun `EPUB download consumes response-scoped stream with cumulative progress`() = runTest {
        val progress = mutableListOf<Pair<Long, Long>>()
        val epubCacheDir = tmp.newFolder("epub-cache")
        val epubDownloadsDir = tmp.newFolder("epub-downloads")
        val repository = EpubRepositoryImpl(
            fileStore = AndroidEpubFileStore(downloadsDir = epubDownloadsDir, cacheDir = epubCacheDir),
            catalogRegistry = registry,
            positionStore = positionStore,
        )

        val result = repository.downloadEpub(item(EbookFormat.Epub)) { downloaded, total ->
            progress += downloaded to total
        }

        assertTrue(result is EpubDownloadResult.Success)
        assertProgress(progress)
        assertEquals(listOf(BookFormat.Epub), catalog.streamedFormats)
    }

    @Test
    fun `PDF download consumes response-scoped stream with cumulative progress`() = runTest {
        val progress = mutableListOf<Pair<Long, Long>>()
        val pdfCacheDir = tmp.newFolder("pdf-cache")
        val pdfDownloadsDir = tmp.newFolder("pdf-downloads")
        val repository = PdfRepositoryImpl(
            fileStore = AndroidPdfFileStore(downloadsDir = pdfDownloadsDir, cacheDir = pdfCacheDir),
            catalogRegistry = registry,
            positionStore = positionStore,
        )

        val result = repository.downloadPdf(item(EbookFormat.Pdf)) { downloaded, total ->
            progress += downloaded to total
        }

        assertTrue(result is PdfDownloadResult.Success)
        assertProgress(progress)
        assertEquals(listOf(BookFormat.Pdf), catalog.streamedFormats)
    }

    @Test
    fun `CBZ download consumes response-scoped stream with cumulative progress`() = runTest {
        val progress = mutableListOf<Pair<Long, Long>>()
        val cbzCacheDir = tmp.newFolder("cbz-cache")
        val cbzDownloadsDir = tmp.newFolder("cbz-downloads")
        val repository = JvmCbzRepositoryImpl(
            fileStore = AndroidCbzFileStore(downloadsDir = cbzDownloadsDir, cacheDir = cbzCacheDir),
            catalogRegistry = registry,
            positionStore = positionStore,
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )

        val result = repository.downloadCbz(item(EbookFormat.Cbz)) { downloaded, total ->
            progress += downloaded to total
        }

        assertTrue(result is CbzDownloadResult.Success)
        assertProgress(progress)
        assertEquals(listOf(BookFormat.Cbz), catalog.streamedFormats)
    }

    private fun assertProgress(progress: List<Pair<Long, Long>>) {
        assertTrue("expected more than one progress update: $progress", progress.size > 1)
        assertTrue("downloaded bytes must be monotonic: $progress", progress.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue("total must stay stable: $progress", progress.all { it.second == payload.size.toLong() })
        assertEquals("100% must only be reported at completion", 1, progress.count { it.first >= it.second })
        assertEquals(payload.size.toLong(), progress.last().first)
    }

    private fun item(format: EbookFormat) = LibraryItem(
        id = "item",
        libraryId = "library",
        title = "Title",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = format,
        ebookFileIno = "ino",
        sourceId = SOURCE_ID,
    )

    private class StreamingOnlyCatalog(private val bytes: ByteArray) : Catalog {
        val streamedFormats = mutableListOf<BookFormat>()

        override val sourceType = SourceType.ABS
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
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle =
            error("fetchFile must not be used")
        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T {
            streamedFormats += format
            return block(
                object : CatalogFileStream {
                    override val contentLength = bytes.size.toLong()
                    override val channel: ByteReadChannel = ByteReadChannel(bytes)
                },
            )
        }
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
    }

    private class EmptyPositionStore : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) = Unit
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) = Unit
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) = Unit
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
    }

    companion object {
        private const val SOURCE_ID = "source"
    }
}
