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
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    private val sourceRepository = TestSourceRepository()
    private val positionStore = EmptyPositionStore()

    @Test
    fun `EPUB download consumes response-scoped stream with cumulative progress`() = runTest {
        val progress = mutableListOf<Pair<Long, Long>>()
        val repository = EpubRepositoryImpl(
            registry,
            store("epub-cache", ".epub"),
            store("epub-downloads", ".epub"),
            positionStore,
            sourceRepository,
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
        val repository = PdfRepositoryImpl(
            registry,
            store("pdf-cache", ".pdf"),
            store("pdf-downloads", ".pdf"),
            positionStore,
            sourceRepository,
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
        val repository = CbzRepositoryImpl(
            registry,
            store("cbz-cache", ".cbz"),
            store("cbz-downloads", ".cbz"),
            positionStore,
            sourceRepository,
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

    private fun store(folder: String, extension: String) =
        LocalStoreImpl(tmp.newFolder(folder), extension, com.riffle.core.domain.DefaultDispatcherProvider)

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
                    override fun byteStream() = bytes.inputStream()
                    override fun close() = Unit
                },
            )
        }
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
    }

    private class TestSourceRepository : SourceRepository {
        private val source = Source(
            id = SOURCE_ID,
            url = com.riffle.core.models.SourceUrl.parse("https://example.test")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "",
        )
        override fun observeAll(): Flow<List<Source>> = flowOf(listOf(source))
        override suspend fun getActive(): Source = source
        override suspend fun getById(sourceId: String): Source? = source.takeIf { it.id == sourceId }
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            com.riffle.core.domain.CommitSourceResult.Success(source)
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
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
