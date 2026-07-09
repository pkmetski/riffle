package com.riffle.core.data.localfiles

import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.Clock
import com.riffle.core.domain.NoOpPdfMetadataExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalFilesScannerTest {

    private val sourceId = "src-1"

    // --- happy path --------------------------------------------------------

    @Test
    fun `empty folder yields empty report`() = runTest {
        val h = harness().apply { configureFolder("f1", files = emptyList()) }
        val report = h.scanner.scan(sourceId)
        assertEquals(LocalFilesScanner.ScanReport(0, 0, 0, emptyList()), report)
        assertTrue(h.libraryItems.upserted.isEmpty())
    }

    @Test
    fun `single EPUB adds one library_items row plus local_files_files row`() = runTest {
        val h = harness().apply {
            configureFolder("f1", files = listOf(fakeEpub("Dune", "Frank Herbert", "book.epub")))
        }
        val report = h.scanner.scan(sourceId)
        assertEquals(1, report.added)
        assertEquals(0, report.refreshed)
        assertEquals(0, report.removed)

        val item = h.libraryItems.upserted.single()
        assertEquals("Dune", item.title)
        assertEquals("Frank Herbert", item.author)
        assertEquals(LocalFilesScanner.LOCAL_ROOT_LIBRARY_ID, item.libraryId)
        assertEquals(LocalFilesScanner.EBOOK_FORMAT_EPUB, item.ebookFormat)
        assertNotNull(item.coverUrl) // fakeEpub embeds a cover

        assertEquals(1, h.files.rows.size)
        val row = h.files.rows.values.single()
        assertEquals(item.id, row.sourceItemId)
        assertNotNull(row.copiedPath)
    }

    @Test
    fun `re-scan is idempotent — refreshed, no duplicate rows`() = runTest {
        val h = harness().apply {
            configureFolder("f1", files = listOf(fakeEpub("Dune", "Herbert", "book.epub")))
        }
        h.scanner.scan(sourceId)
        h.clock.advanceMs(1000)
        val second = h.scanner.scan(sourceId)
        assertEquals(0, second.added)
        assertEquals(1, second.refreshed)
        assertEquals(0, second.removed)
        assertEquals(1, h.files.rows.size)
        // library_items got a single upsert during the first scan and NOT a duplicate on the second.
        assertEquals(1, h.libraryItems.upserted.size)
    }

    @Test
    fun `file removed from folder is hard-deleted`() = runTest {
        val h = harness()
        h.configureFolder("f1", files = listOf(fakeEpub("Dune", "Herbert", "book.epub")))
        h.scanner.scan(sourceId)
        assertEquals(1, h.files.rows.size)

        h.clock.advanceMs(1000)
        h.configureFolder("f1", files = emptyList())
        val second = h.scanner.scan(sourceId)
        assertEquals(0, second.added)
        assertEquals(1, second.removed)
        assertEquals(0, h.files.rows.size)
        assertTrue(h.copyIn.booksOnDisk.isEmpty())
    }

    // --- classification / metadata edge cases ------------------------------

    @Test
    fun `non-epub non-pdf file is skipped with no row`() = runTest {
        val h = harness().apply {
            configureFolder(
                "f1",
                files = listOf(
                    WalkedFile(
                        originalUri = "content://f1/notes.txt",
                        displayName = "notes.txt",
                        sizeBytes = 4,
                        mtimeEpochMs = 0,
                        openStream = { ByteArrayInputStream("hi!\n".toByteArray()) },
                    ),
                ),
            )
        }
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.added)
        assertEquals(0, h.files.rows.size)
    }

    @Test
    fun `EPUB extension with garbage bytes is skipped`() = runTest {
        val h = harness().apply {
            configureFolder(
                "f1",
                files = listOf(
                    WalkedFile(
                        originalUri = "content://f1/fake.epub",
                        displayName = "fake.epub",
                        sizeBytes = 20,
                        mtimeEpochMs = 0,
                        openStream = { ByteArrayInputStream("not a zip at all".toByteArray()) },
                    ),
                ),
            )
        }
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.added)
        assertEquals(0, h.files.rows.size)
    }

    @Test
    fun `EPUB with no metadata falls back to filename-derived title`() = runTest {
        val bytes = buildEpub(opfMetadata = "")
        val h = harness().apply {
            configureFolder(
                "f1",
                files = listOf(
                    WalkedFile(
                        originalUri = "content://f1/My Great Book.epub",
                        displayName = "My Great Book.epub",
                        sizeBytes = bytes.size.toLong(),
                        mtimeEpochMs = 0,
                        openStream = { ByteArrayInputStream(bytes) },
                    ),
                ),
            )
        }
        h.scanner.scan(sourceId)
        val item = h.libraryItems.upserted.single()
        assertEquals("My Great Book", item.title)
        assertEquals("", item.author)
    }

    // --- walker failure -----------------------------------------------------

    @Test
    fun `folder-walk failure records a failure and preserves rows (no stale sweep on incomplete scan)`() = runTest {
        val h = harness()
        // First scan lands one file.
        h.configureFolder("f1", files = listOf(fakeEpub("A", "B", "a.epub")))
        h.scanner.scan(sourceId)
        assertEquals(1, h.files.rows.size)

        // Second scan: walker throws. A transient walker failure must NOT cascade to deletion —
        // otherwise a DocumentsProvider hiccup wipes previously-ingested books whose lastSeenAt
        // was never touched. Row survives; sweep runs on the next fully-clean scan.
        h.clock.advanceMs(1000)
        h.walker.throwFor("f1")
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.removed)
        assertEquals(1, report.failures.size)
        assertEquals(1, h.files.rows.size)
    }

    @Test
    fun `ingest failure suppresses the stale sweep for this pass`() = runTest {
        val h = harness()
        // First scan: two books, both land.
        val good = fakeEpub("Good", "A", "good.epub")
        val bad = WalkedFile(
            originalUri = "content://f1/bad.epub",
            displayName = "bad.epub",
            sizeBytes = 100,
            mtimeEpochMs = 0,
            openStream = { throw java.io.IOException("SAF read failed") },
        )
        h.configureFolder("f1", files = listOf(good))
        h.scanner.scan(sourceId)
        assertEquals(1, h.files.rows.size)

        // Second scan: `bad` is a new file that throws during head-read; `good` is gone. Under the
        // old (unsafe) behavior good's row would be pruned because its lastSeenAt wasn't touched.
        // Correct behavior: the ingest exception suppresses the sweep entirely — good is preserved.
        h.clock.advanceMs(1000)
        h.configureFolder("f1", files = listOf(bad))
        val report = h.scanner.scan(sourceId)
        assertEquals(0, report.removed)
        assertEquals(1, report.failures.size)
        assertEquals(1, h.files.rows.size)
    }

    // --- multi-folder same file --------------------------------------------

    @Test
    fun `same file in two folders resolves to one row and survives removal from one`() = runTest {
        val bytes = buildEpub(opfMetadata = """<dc:title>Shared</dc:title>""")
        fun sharedFile(uri: String) = WalkedFile(
            originalUri = uri,
            displayName = "shared.epub",
            sizeBytes = bytes.size.toLong(),
            mtimeEpochMs = 0,
            openStream = { ByteArrayInputStream(bytes) },
        )
        val h = harness()
        h.configureFolder("f1", files = listOf(sharedFile("content://f1/shared.epub")))
        h.configureFolder("f2", files = listOf(sharedFile("content://f2/shared.epub")))
        val first = h.scanner.scan(sourceId)
        assertEquals(1, first.added)     // ← the whole point: same identity hash ⇒ one row.
        assertEquals(1, first.refreshed) // ← touched by the second folder's scan pass.
        assertEquals(1, h.files.rows.size)

        // Remove from f1 only; f2 still has it → row survives.
        h.clock.advanceMs(1000)
        h.configureFolder("f1", files = emptyList())
        val second = h.scanner.scan(sourceId)
        assertEquals(0, second.removed)
        assertEquals(1, h.files.rows.size)

        // Remove from f2 too → row deleted.
        h.clock.advanceMs(1000)
        h.configureFolder("f2", files = emptyList())
        val third = h.scanner.scan(sourceId)
        assertEquals(1, third.removed)
        assertEquals(0, h.files.rows.size)
    }

    // --- harness ------------------------------------------------------------

    private fun harness(): Harness = Harness()

    private class Harness {
        val libraryItems = FakeLibraryItemDao()
        val folders = InMemoryFolderDao()
        val files = InMemoryFileDao()
        val walker = InMemoryWalker()
        val copyIn = InMemoryCopyIn()
        val clock = MutableClock()
        val scanner = LocalFilesScanner(
            folderDao = folders,
            fileDao = files,
            libraryItemDao = libraryItems,
            walker = walker,
            copyIn = copyIn,
            pdfMetadata = NoOpPdfMetadataExtractor,
            clock = clock,
            logger = com.riffle.core.logging.NoopLogger,
        )

        suspend fun configureFolder(treeUri: String, files: List<WalkedFile>) {
            folders.upsert(LocalFilesFolderEntity("src-1", treeUri, treeUri, clock.nowMs()))
            walker.set(treeUri, files)
        }
    }

    private fun fakeEpub(title: String, author: String, displayName: String): WalkedFile {
        val bytes = buildEpub(
            opfMetadata = """
                <dc:title>$title</dc:title>
                <dc:creator>$author</dc:creator>
            """.trimIndent(),
            extraManifestItems = """<item id="c" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
            extraZipEntries = listOf("cover.jpg" to ByteArray(16) { it.toByte() }),
        )
        return WalkedFile(
            originalUri = "content://tree/$displayName",
            displayName = displayName,
            sizeBytes = bytes.size.toLong(),
            mtimeEpochMs = 0,
            openStream = { ByteArrayInputStream(bytes) },
        )
    }

    private class MutableClock : Clock {
        private var t = 1_000L
        fun advanceMs(by: Long) { t += by }
        override fun nowMs(): Long = t
        override fun nowNs(): Long = t * 1_000_000
    }

    private class InMemoryFolderDao : LocalFilesFolderDao {
        val store = mutableMapOf<Pair<String, String>, LocalFilesFolderEntity>()
        override suspend fun upsert(entity: LocalFilesFolderEntity) {
            store[entity.sourceId to entity.treeUri] = entity
        }
        override suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity> =
            store.values.filter { it.sourceId == sourceId }.sortedBy { it.addedAtEpochMs }
        override fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>> =
            MutableStateFlow(store.values.filter { it.sourceId == sourceId })
        override suspend fun delete(sourceId: String, treeUri: String) {
            store.remove(sourceId to treeUri)
        }
    }

    private class InMemoryFileDao : LocalFilesFileDao {
        val rows = mutableMapOf<Pair<String, String>, LocalFilesFileEntity>()
        override suspend fun upsert(entity: LocalFilesFileEntity) {
            rows[entity.sourceId to entity.sourceItemId] = entity
        }
        override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? =
            rows[sourceId to sourceItemId]
        override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> =
            rows.values.filter { it.sourceId == sourceId }
        override suspend fun touchLastSeen(
            sourceId: String,
            sourceItemId: String,
            folderTreeUri: String,
            seenAt: Long,
        ) {
            val row = rows[sourceId to sourceItemId] ?: return
            rows[sourceId to sourceItemId] =
                row.copy(lastSeenAtEpochMs = seenAt, folderTreeUri = folderTreeUri)
        }
        override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileEntity> =
            rows.values.filter { it.sourceId == sourceId && it.lastSeenAtEpochMs < scanStart }
        override suspend fun delete(sourceId: String, sourceItemId: String) {
            rows.remove(sourceId to sourceItemId)
        }
    }

    private class InMemoryWalker : FolderWalker {
        private val entries = mutableMapOf<String, List<WalkedFile>>()
        private val failing = mutableSetOf<String>()
        fun set(treeUri: String, files: List<WalkedFile>) {
            entries[treeUri] = files
            failing.remove(treeUri)
        }
        fun throwFor(treeUri: String) { failing += treeUri }
        override suspend fun walk(treeUri: String): List<WalkedFile> {
            if (treeUri in failing) throw IllegalStateException("boom")
            return entries[treeUri].orEmpty()
        }
    }

    private class InMemoryCopyIn : CopyInService {
        val booksOnDisk = mutableMapOf<String, ByteArray>()
        val coversOnDisk = mutableMapOf<String, ByteArray>()
        private val tmp: File = createTempDirectory()

        override suspend fun copyBook(sourceId: String, sourceItemId: String, extension: String, stream: InputStream): File {
            val bytes = stream.readBytes()
            booksOnDisk["$sourceId/$sourceItemId"] = bytes
            val f = File(tmp, "$sourceId-$sourceItemId.$extension")
            f.writeBytes(bytes)
            return f
        }

        override suspend fun writeCover(sourceId: String, sourceItemId: String, extension: String, bytes: ByteArray): File {
            coversOnDisk["$sourceId/$sourceItemId"] = bytes
            val f = File(tmp, "cover-$sourceId-$sourceItemId.$extension")
            f.writeBytes(bytes)
            return f
        }

        override suspend fun deleteBook(sourceId: String, sourceItemId: String) {
            booksOnDisk.remove("$sourceId/$sourceItemId")
        }

        override suspend fun deleteCover(sourceId: String, sourceItemId: String) {
            coversOnDisk.remove("$sourceId/$sourceItemId")
        }

        private fun createTempDirectory(): File =
            java.nio.file.Files.createTempDirectory("localfiles-test").toFile().also { it.deleteOnExit() }
    }
}

private fun buildEpub(
    opfMetadata: String,
    extraManifestItems: String = "",
    extraZipEntries: List<Pair<String, ByteArray>> = emptyList(),
): ByteArray {
    val container = """<?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()
    val opf = """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0">
          <metadata>
            $opfMetadata
          </metadata>
          <manifest>
            <item id="ch1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
            $extraManifestItems
          </manifest>
          <spine><itemref idref="ch1"/></spine>
        </package>
    """.trimIndent()
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write(container.toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf")); zip.write(opf.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/chap1.xhtml")); zip.write("<html/>".toByteArray()); zip.closeEntry()
        for ((name, bytes) in extraZipEntries) {
            zip.putNextEntry(ZipEntry("OEBPS/$name")); zip.write(bytes); zip.closeEntry()
        }
    }
    return out.toByteArray()
}
