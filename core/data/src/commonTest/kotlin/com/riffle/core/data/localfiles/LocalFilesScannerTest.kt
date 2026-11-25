package com.riffle.core.data.localfiles

import com.riffle.core.common.Clock
import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.logging.NoopLogger
import com.riffle.core.models.EpubMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFilesScannerTest {

    private val sourceId = "src-1"

    @Test
    fun `empty folder yields empty report`() = runTest {
        val h = harness().apply { configureFolder("f1", files = emptyList()) }
        val report = h.scanner.scan(sourceId)
        assertEquals(LocalFilesScanner.ScanReport(0, 0, 0, emptyList()), report)
        assertTrue(h.libraryItems.upserted.isEmpty())
    }

    @Test
    fun `single EPUB adds one library_items row plus local_files_files row plus one membership`() = runTest {
        val h = harness().apply {
            metadata.epubResult = epubMetadata(title = "Dune", author = "Frank Herbert", withCover = true)
            configureFolder("f1", files = listOf(epubFile("book.epub")))
        }
        val report = h.scanner.scan(sourceId)
        assertEquals(1, report.added)
        assertEquals(0, report.refreshed)
        assertEquals(0, report.removed)

        val item = h.libraryItems.upserted.single()
        assertEquals("Dune", item.title)
        assertEquals("Frank Herbert", item.author)
        assertEquals("lib-f1", item.libraryId)
        assertEquals(LocalFilesScanner.EBOOK_FORMAT_EPUB, item.ebookFormat)
        assertNotNull(item.coverUrl)
        assertTrue(item.coverUrl!!.startsWith("file://"))

        assertEquals(1, h.files.rows.size)
        val row = h.files.rows.values.single()
        assertEquals(item.id, row.sourceItemId)
        assertNotNull(row.copiedPath)

        assertEquals(1, h.memberships.rows.size)
        assertEquals("f1", h.memberships.rows.values.single().folderTreeUri)
    }

    @Test
    fun `re-scan is idempotent — refreshed without duplicate rows`() = runTest {
        val h = harness().apply {
            metadata.epubResult = epubMetadata(title = "Dune", author = "Herbert", withCover = true)
            configureFolder("f1", files = listOf(epubFile("book.epub")))
        }
        h.scanner.scan(sourceId)
        h.clock.advanceMs(1000)
        val second = h.scanner.scan(sourceId)
        assertEquals(0, second.added)
        assertEquals(1, second.refreshed)
        assertEquals(0, second.removed)
        assertEquals(1, h.files.rows.size)
        assertEquals(1, h.libraryItems.upserted.size)
        assertEquals(1, h.memberships.rows.size)
    }

    @Test
    fun `file removed from folder is hard-deleted`() = runTest {
        val h = harness()
        h.metadata.epubResult = epubMetadata(title = "Dune", author = "Herbert", withCover = true)
        h.configureFolder("f1", files = listOf(epubFile("book.epub")))
        h.scanner.scan(sourceId)
        assertEquals(1, h.files.rows.size)
        assertEquals(1, h.memberships.rows.size)

        h.clock.advanceMs(1000)
        h.configureFolder("f1", files = emptyList())
        val second = h.scanner.scan(sourceId)
        assertEquals(0, second.added)
        assertEquals(1, second.removed)
        assertEquals(0, h.files.rows.size)
        assertEquals(0, h.memberships.rows.size)
        assertTrue(h.copyIn.booksOnDisk.isEmpty())
    }

    @Test
    fun `non-epub non-pdf file is skipped with no row`() = runTest {
        val h = harness().apply {
            configureFolder("f1", files = listOf(rawFile("notes.txt", "hi!\n".encodeToByteArray())))
        }
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.added)
        assertEquals(0, h.files.rows.size)
    }

    @Test
    fun `EPUB extension with garbage bytes is skipped`() = runTest {
        val h = harness().apply {
            configureFolder("f1", files = listOf(rawFile("fake.epub", "not a zip at all".encodeToByteArray())))
        }
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.added)
        assertEquals(0, h.files.rows.size)
    }

    @Test
    fun `EPUB with no metadata falls back to filename-derived title`() = runTest {
        val h = harness().apply {
            metadata.epubResult = EpubMetadata.EMPTY
            configureFolder("f1", files = listOf(epubFile("My Great Book.epub")))
        }
        h.scanner.scan(sourceId)
        val item = h.libraryItems.upserted.single()
        assertEquals("My Great Book", item.title)
        assertEquals("", item.author)
    }

    @Test
    fun `folder-walk failure records a failure and preserves rows - no stale sweep`() = runTest {
        val h = harness()
        h.metadata.epubResult = epubMetadata(title = "A", author = "B", withCover = true)
        h.configureFolder("f1", files = listOf(epubFile("a.epub")))
        h.scanner.scan(sourceId)
        assertEquals(1, h.files.rows.size)

        h.clock.advanceMs(1000)
        h.walker.throwFor("f1")
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.removed)
        assertEquals(1, report.failures.size)
        assertEquals(1, h.files.rows.size)
        assertEquals(1, h.memberships.rows.size)
    }

    @Test
    fun `ingest failure suppresses the stale sweep for this pass`() = runTest {
        val h = harness()
        h.metadata.epubResult = epubMetadata(title = "Good", author = "A", withCover = true)
        val bad = WalkedFile(
            originalUri = "content://f1/bad.epub",
            displayName = "bad.epub",
            sizeBytes = 100,
            mtimeEpochMs = 0,
            openSource = { throw okio.IOException("SAF read failed") },
        )
        h.configureFolder("f1", files = listOf(epubFile("good.epub")))
        h.scanner.scan(sourceId)
        assertEquals(1, h.files.rows.size)

        h.clock.advanceMs(1000)
        h.configureFolder("f1", files = listOf(bad))
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.removed)
        assertEquals(1, report.failures.size)
        assertEquals(1, h.files.rows.size)
    }

    @Test
    fun `same file in two folders resolves to one row but has two folder memberships and survives removal from one`() = runTest {
        val bytes = epubBytes(padding = 9)
        fun sharedFile(uri: String) = WalkedFile(
            originalUri = uri,
            displayName = "shared.epub",
            sizeBytes = bytes.size.toLong(),
            mtimeEpochMs = 0,
            openSource = { Buffer().write(bytes) },
        )
        val h = harness()
        h.metadata.epubResult = epubMetadata(title = "Shared", author = "", withCover = true)
        h.configureFolder("f1", files = listOf(sharedFile("content://f1/shared.epub")))
        h.configureFolder("f2", files = listOf(sharedFile("content://f2/shared.epub")))
        val first = h.scanner.scan(sourceId)
        assertEquals(1, first.added)
        assertEquals(1, first.refreshed)
        assertEquals(1, h.files.rows.size)
        assertEquals(2, h.memberships.rows.size)
        assertEquals(setOf("f1", "f2"), h.memberships.rows.values.map { it.folderTreeUri }.toSet())

        h.clock.advanceMs(1000)
        h.configureFolder("f1", files = emptyList())
        val second = h.scanner.scan(sourceId)
        assertEquals(0, second.removed)
        assertEquals(1, h.files.rows.size)
        assertEquals(1, h.memberships.rows.size)
        assertEquals("f2", h.memberships.rows.values.single().folderTreeUri)

        h.clock.advanceMs(1000)
        h.configureFolder("f2", files = emptyList())
        val third = h.scanner.scan(sourceId)
        assertEquals(1, third.removed)
        assertEquals(0, h.files.rows.size)
        assertEquals(0, h.memberships.rows.size)
    }

    // --- harness ------------------------------------------------------------

    private fun harness(): Harness = Harness()

    private class Harness {
        val libraryItems = FakeLibraryItemDao()
        val folders = InMemoryLocalFilesFolderDao()
        val files = InMemoryLocalFilesFileDao()
        val memberships = InMemoryLocalFilesFileFolderDao(files)
        val walker = InMemoryWalker()
        val copyIn = InMemoryCopyIn()
        val metadata = FakeLocalBookMetadataReader()
        val clock = MutableClock()
        val scanner = LocalFilesScanner(
            folderDao = folders,
            fileDao = files,
            fileFolderDao = memberships,
            libraryItemDao = libraryItems,
            walker = walker,
            copyIn = copyIn,
            metadataReader = metadata,
            dispatchers = UnconfinedDispatchers,
            clock = clock,
            logger = NoopLogger,
        )

        suspend fun configureFolder(treeUri: String, files: List<WalkedFile>) {
            folders.upsert(
                LocalFilesFolderEntity(
                    sourceId = "src-1",
                    treeUri = treeUri,
                    displayName = treeUri,
                    addedAtEpochMs = clock.nowMs(),
                    libraryId = "lib-$treeUri",
                ),
            )
            walker.set(treeUri, files)
        }
    }

    private class MutableClock : Clock {
        private var t = 1_000L
        fun advanceMs(by: Long) {
            t += by
        }
        override fun nowMs(): Long = t
        override fun nowNs(): Long = t * 1_000_000
    }

    private class InMemoryWalker : FolderWalker {
        private val entries = mutableMapOf<String, List<WalkedFile>>()
        private val failing = mutableSetOf<String>()
        fun set(treeUri: String, files: List<WalkedFile>) {
            entries[treeUri] = files
            failing.remove(treeUri)
        }
        fun throwFor(treeUri: String) {
            failing += treeUri
        }
        override suspend fun walk(treeUri: String): List<WalkedFile> {
            if (treeUri in failing) throw IllegalStateException("boom")
            return entries[treeUri].orEmpty()
        }
    }

    private class InMemoryCopyIn : CopyInService {
        val booksOnDisk = mutableMapOf<String, ByteArray>()
        val coversOnDisk = mutableMapOf<String, ByteArray>()

        override suspend fun copyBook(sourceId: String, sourceItemId: String, extension: String, source: Source): String {
            val bytes = Buffer().apply { writeAll(source) }.readByteArray()
            source.close()
            booksOnDisk["$sourceId/$sourceItemId"] = bytes
            return "/localfiles-test/$sourceId-$sourceItemId.$extension"
        }

        override suspend fun writeCover(sourceId: String, sourceItemId: String, extension: String, bytes: ByteArray): String {
            coversOnDisk["$sourceId/$sourceItemId"] = bytes
            return "/localfiles-test/cover-$sourceId-$sourceItemId.$extension"
        }

        override suspend fun deleteBook(sourceId: String, sourceItemId: String) {
            booksOnDisk.remove("$sourceId/$sourceItemId")
        }

        override suspend fun deleteCover(sourceId: String, sourceItemId: String) {
            coversOnDisk.remove("$sourceId/$sourceItemId")
        }
    }

    private class FakeLocalBookMetadataReader : LocalBookMetadataReader {
        var epubResult: EpubMetadata = EpubMetadata.EMPTY
        var pdfResult: LocalPdfMetadata = LocalPdfMetadata(title = null, author = null, subject = null)
        var cbzResult: LocalCbzMetadata = LocalCbzMetadata(coverBytes = null, coverExtension = null, pageCount = 0)
        override suspend fun epub(copiedPath: String): EpubMetadata = epubResult
        override suspend fun pdf(copiedPath: String): LocalPdfMetadata = pdfResult
        override suspend fun cbz(copiedPath: String): LocalCbzMetadata = cbzResult
    }

    private object UnconfinedDispatchers : DispatcherProvider {
        private val d: CoroutineDispatcher = UnconfinedTestDispatcher()
        override val main: CoroutineDispatcher get() = d
        override val mainImmediate: CoroutineDispatcher get() = d
        override val io: CoroutineDispatcher get() = d
        override val default: CoroutineDispatcher get() = d
    }

    private fun epubMetadata(title: String, author: String, withCover: Boolean): EpubMetadata =
        EpubMetadata.EMPTY.copy(
            title = title,
            author = author,
            coverBytes = if (withCover) ByteArray(16) { it.toByte() } else null,
            coverExtension = if (withCover) "jpg" else null,
        )

    private fun epubBytes(padding: Byte = 0): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(32) { padding }

    private fun epubFile(displayName: String, padding: Byte = 0): WalkedFile {
        val bytes = epubBytes(padding)
        return WalkedFile(
            originalUri = "content://tree/$displayName",
            displayName = displayName,
            sizeBytes = bytes.size.toLong(),
            mtimeEpochMs = 0,
            openSource = { Buffer().write(bytes) },
        )
    }

    private fun rawFile(displayName: String, bytes: ByteArray): WalkedFile = WalkedFile(
        originalUri = "content://f1/$displayName",
        displayName = displayName,
        sizeBytes = bytes.size.toLong(),
        mtimeEpochMs = 0,
        openSource = { Buffer().write(bytes) },
    )
}
