package com.riffle.shared.library

import com.riffle.core.common.FileStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real iOS implementations that replaced IosNoOpPdfRepository /
 * IosNoOpPdfPageCountExtractor / IosNoOpCoverImageCopier (issue #1065): PDFKit page counting
 * against a genuine generated PDF, the metrics cache contract Android's
 * ExtractPdfPageCountUseCase also honours, and a security-scoped cover copy.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPdfAndCoverTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "pdfcover_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(base, true, null, null)
            val full = if (relativePath.isEmpty()) base else "$base/$relativePath"
            full.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
            }
            return full
        }
    }

    private object NoopPositionStore : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) = Unit
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) = Unit
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) = Unit
    }

    private object NoopSourceRepository : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private object NoopTokenStorage : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private val unusedClient = HttpClient(MockEngine { respondOk() })

    private fun pdfRepo(fileStore: FileStore) =
        IosPdfRepositoryImpl(NoopPositionStore, fileStore, NoopSourceRepository, NoopTokenStorage, unusedClient)

    private fun writeFile(path: String, bytes: ByteArray): Boolean {
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        return data.writeToFile(path, atomically = true)
    }

    private class RecordingMetricsRepository(
        private var stored: PublicationMetrics? = null,
    ) : PublicationMetricsRepository {
        var saves = 0
        override suspend fun get(sourceId: String, itemId: String): PublicationMetrics? = stored
        override suspend fun save(sourceId: String, itemId: String, metrics: PublicationMetrics) {
            stored = metrics
            saves++
        }
    }

    private fun item(inode: String? = "ino-1") = LibraryItem(
        id = "i1",
        sourceId = "s1",
        libraryId = "lib1",
        title = "Doc",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Pdf,
        ebookFileIno = inode,
    )

    /** Writes a real 3-page PDF so PDFKit has something genuine to count. */
    private fun writePdf(path: String, pages: Int): Boolean {
        val doc = PDFDocument()
        repeat(pages) { index -> doc.insertPage(PDFPage(), atIndex = index.toULong()) }
        val data = doc.dataRepresentation() ?: return false
        return data.writeToFile(path, atomically = true)
    }

    @Test
    fun `pdf repository reports not downloaded until a file exists`() {
        val fileStore = TempFileStore()
        val repo = pdfRepo(fileStore)

        assertTrue(!repo.isDownloaded("s1", "i1"))
        assertNull(repo.localPath("s1", "i1"))
    }

    @Test
    fun `pdf repository sees a downloaded file and prefers it over the cache copy`() {
        val fileStore = TempFileStore()
        val repo = pdfRepo(fileStore)
        val downloadPath = fileStore.resolve("pdf-downloads", IosPdfPaths.downloadRelativePath("s1", "i1"))
        assertTrue(writePdf(downloadPath, pages = 1), "precondition: wrote the PDF")

        assertTrue(repo.isDownloaded("s1", "i1"))
        assertEquals(downloadPath, repo.localPath("s1", "i1"))
    }

    @Test
    fun `page count extractor reads the real page count through PDFKit`() = runTest {
        val fileStore = TempFileStore()
        val repo = pdfRepo(fileStore)
        writePdf(fileStore.resolve("pdf-downloads", IosPdfPaths.downloadRelativePath("s1", "i1")), pages = 3)
        val metrics = RecordingMetricsRepository()

        val count = IosPdfPageCountExtractor(repo::localPath, metrics).extract(item())

        assertEquals(3, count)
        assertEquals(1, metrics.saves, "a freshly computed count must be cached")
    }

    @Test
    fun `page count extractor reuses a cached count for the same file inode`() = runTest {
        val fileStore = TempFileStore()
        val repo = pdfRepo(fileStore)
        // No file on disk at all: only the cache can answer.
        val metrics = RecordingMetricsRepository(PublicationMetrics(ebookFileIno = "ino-1", pageCount = 11))

        val count = IosPdfPageCountExtractor(repo::localPath, metrics).extract(item(inode = "ino-1"))

        assertEquals(11, count)
        assertEquals(0, metrics.saves, "a cache hit must not re-save")
    }

    @Test
    fun `page count extractor ignores a cached count from a different file inode`() = runTest {
        val fileStore = TempFileStore()
        val repo = pdfRepo(fileStore)
        writePdf(fileStore.resolve("pdf-downloads", IosPdfPaths.downloadRelativePath("s1", "i1")), pages = 2)
        val metrics = RecordingMetricsRepository(PublicationMetrics(ebookFileIno = "stale-ino", pageCount = 99))

        val count = IosPdfPageCountExtractor(repo::localPath, metrics).extract(item(inode = "ino-1"))

        assertEquals(2, count, "a re-uploaded file must be re-counted, not served from the stale entry")
    }

    @Test
    fun `page count extractor returns null when the pdf is not stored locally`() = runTest {
        val fileStore = TempFileStore()
        val repo = pdfRepo(fileStore)

        assertNull(IosPdfPageCountExtractor(repo::localPath, RecordingMetricsRepository()).extract(item()))
    }

    @Test
    fun `cover copier stores a picked image and returns a stable file url`() = runTest {
        val fileStore = TempFileStore()
        val sourcePath = NSTemporaryDirectory() + "cover_" + NSUUID().UUIDString() + ".jpg"
        roots += sourcePath
        assertTrue(writeFile(sourcePath, ByteArray(64) { 9 }), "precondition: wrote source image")

        val copied = IosCoverImageCopier(fileStore).invoke("s1", "i1", NSURL.fileURLWithPath(sourcePath).absoluteString!!)

        assertNotNull(copied)
        assertTrue(copied.startsWith("file://"), "expected a file URL, got $copied")
        val storedPath = NSURL.URLWithString(copied)?.path
        assertNotNull(storedPath)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(storedPath), "the copy must exist on disk")
    }

    @Test
    fun `cover copier returns null for a missing source image`() = runTest {
        val copied = IosCoverImageCopier(TempFileStore())
            .invoke("s1", "i1", NSURL.fileURLWithPath(NSTemporaryDirectory() + "absent.jpg").absoluteString!!)

        assertNull(copied)
    }
}
