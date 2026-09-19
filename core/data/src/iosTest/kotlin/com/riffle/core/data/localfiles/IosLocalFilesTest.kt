package com.riffle.core.data.localfiles

import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.TestClock
import com.riffle.core.models.EbookFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for #1056: `IosLocalFilesScanner` and `IosLocalFilesFolderRepository` are
 * fully-written iOS implementations that Koin used to shadow with no-op bindings, so the Local
 * Files source silently did nothing on iOS. These exercise the real classes end-to-end (real
 * filesystem I/O via NSFileManager/posix, same as production) the way Koin now wires them.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalFilesTest {

    private val sourceId = "src-1"
    private val dispatchers = IosDispatcherProvider
    private val walker = IosFolderWalker(dispatchers)
    private val copyIn = IosCopyInService(dispatchers)
    private val tempDirs = mutableListOf<String>()
    private val copyInDirs = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        val manager = NSFileManager.defaultManager
        tempDirs.forEach { manager.removeItemAtPath(it, error = null) }
        copyInDirs.forEach { manager.removeItemAtPath(it, error = null) }
    }

    /**
     * A fresh scanner over fresh in-memory DAOs, scoped to a source id nobody else uses so the
     * real [IosCopyInService] (which writes into the app's Documents directory) cannot collide
     * with another test's copied books.
     */
    private fun fixture(
        clock: TestClock = TestClock(1_000L),
        fileDao: InMemoryLocalFilesFileDao = InMemoryLocalFilesFileDao(),
    ): Fixture {
        val id = "src-" + NSUUID().UUIDString()
        copyInDirs += documentsDirectory() + "/localfiles/" + id
        val folderDao = InMemoryLocalFilesFolderDao()
        val fileFolderDao = InMemoryLocalFilesFileFolderDao(fileDao)
        val libraryItemDao = FakeLibraryItemDao()
        return Fixture(
            sourceId = id,
            clock = clock,
            folderDao = folderDao,
            fileDao = fileDao,
            fileFolderDao = fileFolderDao,
            libraryItemDao = libraryItemDao,
            scanner = IosLocalFilesScanner(
                folderDao, fileDao, fileFolderDao, libraryItemDao, walker, copyIn, dispatchers, clock,
            ),
        )
    }

    private class Fixture(
        val sourceId: String,
        val clock: TestClock,
        val folderDao: InMemoryLocalFilesFolderDao,
        val fileDao: InMemoryLocalFilesFileDao,
        val fileFolderDao: InMemoryLocalFilesFileFolderDao,
        val libraryItemDao: FakeLibraryItemDao,
        val scanner: IosLocalFilesScanner,
    ) {
        suspend fun configureFolder(path: String, libraryId: String, addedAtEpochMs: Long = 0L) {
            folderDao.upsert(
                LocalFilesFolderEntity(
                    sourceId = sourceId,
                    treeUri = path,
                    displayName = path.substringAfterLast('/'),
                    addedAtEpochMs = addedAtEpochMs,
                    libraryId = libraryId,
                ),
            )
        }
    }

    private fun documentsDirectory(): String {
        @Suppress("UNCHECKED_CAST")
        return (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true) as List<String>)
            .first()
    }

    private fun newFolder(): String {
        val path = NSTemporaryDirectory() + "riffle-localfiles-test-" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        tempDirs += path
        return path
    }

    private fun writeFile(folderPath: String, name: String, bytes: ByteArray) {
        val path = "$folderPath/$name"
        bytes.usePinned { pinned ->
            val f = fopen(path, "wb") ?: error("cannot open $path for writing")
            fwrite(pinned.addressOf(0), 1.toULong(), bytes.size.toULong(), f)
            fclose(f)
        }
    }

    private fun epubBytes(paddingByte: Byte = 0): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(32) { paddingByte }

    @Test
    fun `scan discovers new files and adds them to the library`() = runTest {
        val folderDao = InMemoryLocalFilesFolderDao()
        val fileDao = InMemoryLocalFilesFileDao()
        val fileFolderDao = InMemoryLocalFilesFileFolderDao(fileDao)
        val libraryItemDao = FakeLibraryItemDao()
        val clock = TestClock(1_000L)
        val scanner = IosLocalFilesScanner(folderDao, fileDao, fileFolderDao, libraryItemDao, walker, copyIn, dispatchers, clock)

        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes())
        folderDao.upsert(
            LocalFilesFolderEntity(sourceId, folderPath, "My Folder", addedAtEpochMs = 0L, libraryId = "lib-1"),
        )

        val report = scanner.scan(sourceId)

        assertEquals(1, report.added)
        assertEquals(1, libraryItemDao.upserted.size)
        assertEquals(EbookFormat.STORAGE_EPUB, libraryItemDao.upserted.single().ebookFormat)
        assertEquals(1, fileDao.rows.size)
        assertEquals(1, fileFolderDao.rows.size)
    }

    @Test
    fun `scan removes items for files that no longer exist`() = runTest {
        val folderDao = InMemoryLocalFilesFolderDao()
        val fileDao = InMemoryLocalFilesFileDao()
        val fileFolderDao = InMemoryLocalFilesFileFolderDao(fileDao)
        val libraryItemDao = FakeLibraryItemDao()
        val clock = TestClock(1_000L)
        val scanner = IosLocalFilesScanner(folderDao, fileDao, fileFolderDao, libraryItemDao, walker, copyIn, dispatchers, clock)

        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 1))
        folderDao.upsert(
            LocalFilesFolderEntity(sourceId, folderPath, "My Folder", addedAtEpochMs = 0L, libraryId = "lib-1"),
        )
        val first = scanner.scan(sourceId)
        assertEquals(1, first.added)

        clock.advance(1_000L)
        NSFileManager.defaultManager.removeItemAtPath("$folderPath/book.epub", error = null)
        val second = scanner.scan(sourceId)

        assertEquals(1, second.removed)
        assertTrue(fileDao.rows.isEmpty())
        assertTrue(fileFolderDao.rows.isEmpty())
        assertTrue(libraryItemDao.itemsFor("lib-1").isEmpty())
    }

    @Test
    fun `scan handles an unreadable folder gracefully`() = runTest {
        val folderDao = InMemoryLocalFilesFolderDao()
        val fileDao = InMemoryLocalFilesFileDao()
        val fileFolderDao = InMemoryLocalFilesFileFolderDao(fileDao)
        val libraryItemDao = FakeLibraryItemDao()
        val scanner = IosLocalFilesScanner(
            folderDao, fileDao, fileFolderDao, libraryItemDao, walker, copyIn, dispatchers, TestClock(1_000L),
        )

        folderDao.upsert(
            LocalFilesFolderEntity(
                sourceId,
                NSTemporaryDirectory() + "riffle-localfiles-test-does-not-exist-" + NSUUID().UUIDString(),
                "Missing Folder",
                addedAtEpochMs = 0L,
                libraryId = "lib-1",
            ),
        )

        val report = scanner.scan(sourceId)

        assertEquals(0, report.added)
        assertTrue(libraryItemDao.upserted.isEmpty())
        assertEquals(1, report.failures.size, "a folder that cannot be listed is reported, not silently empty")
    }

    @Test
    fun `removeFolder deletes the folder its memberships and its library`() = runTest {
        val folderDao = InMemoryLocalFilesFolderDao()
        val libraryDao = InMemoryLibraryDao()
        val fileFolderDao = InMemoryLocalFilesFileFolderDao(InMemoryLocalFilesFileDao())
        val repository = IosLocalFilesFolderRepository(folderDao, libraryDao, fileFolderDao, TestClock(1_000L))

        val folderPath = newFolder()
        val libraryId = repository.addFolder(sourceId, FolderUri(folderPath))
        fileFolderDao.upsert(LocalFilesFileFolderEntity(sourceId, "item-1", folderPath, lastSeenAtEpochMs = 0L))
        assertTrue(folderDao.forSource(sourceId).isNotEmpty())
        assertTrue(libraryDao.libraryIdsForSource(sourceId).contains(libraryId))

        repository.removeFolder(sourceId, folderPath)

        assertTrue(folderDao.forSource(sourceId).isEmpty())
        assertTrue(libraryDao.libraryIdsForSource(sourceId).isEmpty())
        assertTrue(fileFolderDao.forFolder(sourceId, folderPath).isEmpty())
    }

    // --- parity with LocalFilesScannerTest (androidHostTest) ------------------------------------

    @Test
    fun `scan of an empty folder yields an empty report`() = runTest {
        val f = fixture()
        f.configureFolder(newFolder(), libraryId = "lib-1")

        val report = f.scanner.scan(f.sourceId)

        assertEquals(IosLocalFilesScanner.ScanReport(0, 0, 0, emptyList()), report)
        assertTrue(f.libraryItemDao.upserted.isEmpty())
    }

    @Test
    fun `an ingested book records its folder library and its copied path`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 11))
        f.configureFolder(folderPath, libraryId = "lib-42")

        f.scanner.scan(f.sourceId)

        val item = f.libraryItemDao.upserted.single()
        assertEquals("lib-42", item.libraryId, "the item must be filed under the folder's own library")
        assertEquals(EbookFormat.STORAGE_EPUB, item.ebookFormat)
        val row = f.fileDao.rows.values.single()
        assertEquals(item.id, row.sourceItemId)
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(row.copiedPath),
            "the book bytes must be copied into app-private storage",
        )
        assertEquals(folderPath, f.fileFolderDao.rows.values.single().folderTreeUri)
    }

    @Test
    fun `a re-scan refreshes without duplicating rows`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 2))
        f.configureFolder(folderPath, libraryId = "lib-1")
        f.scanner.scan(f.sourceId)

        f.clock.advance(1_000L)
        val second = f.scanner.scan(f.sourceId)

        assertEquals(0, second.added)
        assertEquals(1, second.refreshed)
        assertEquals(0, second.removed)
        assertEquals(1, f.fileDao.rows.size)
        assertEquals(1, f.fileFolderDao.rows.size)
        assertEquals(1, f.libraryItemDao.upserted.size, "a refresh must not re-insert the library row")
    }

    @Test
    fun `a file that is neither epub nor pdf nor cbz is skipped`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "notes.txt", "hi!\n".encodeToByteArray())
        f.configureFolder(folderPath, libraryId = "lib-1")

        val report = f.scanner.scan(f.sourceId)

        assertEquals(0, report.added)
        assertTrue(f.fileDao.rows.isEmpty())
        assertTrue(f.libraryItemDao.upserted.isEmpty())
    }

    @Test
    fun `an epub extension over garbage bytes is skipped`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "fake.epub", "not a zip at all".encodeToByteArray())
        f.configureFolder(folderPath, libraryId = "lib-1")

        val report = f.scanner.scan(f.sourceId)

        assertEquals(0, report.added, "extension alone must not be enough — the magic bytes must agree")
        assertTrue(f.fileDao.rows.isEmpty())
    }

    @Test
    fun `the title falls back to the filename without its extension`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "My Great Book.epub", epubBytes(paddingByte = 3))
        f.configureFolder(folderPath, libraryId = "lib-1")

        f.scanner.scan(f.sourceId)

        val item = f.libraryItemDao.upserted.single()
        assertEquals("My Great Book", item.title)
        assertEquals("", item.author)
    }

    /**
     * Regression test for a real iOS defect: [IosFolderWalker] used to swallow the
     * `contentsOfDirectoryAtPath` failure and return an empty list, which the scanner could not
     * tell apart from "the user emptied this folder". The stale sweep then hard-deleted every book
     * the folder had contributed — library rows, junction rows and the copied bytes — the moment
     * the folder was moved, deleted or its volume unmounted. Android's `SafFolderWalker` throws in
     * the same situation precisely so the sweep is suppressed.
     */
    @Test
    fun `a folder that cannot be listed records a failure and preserves rows`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 5))
        f.configureFolder(folderPath, libraryId = "lib-1")
        assertEquals(1, f.scanner.scan(f.sourceId).added, "precondition: the book was ingested")

        f.clock.advance(1_000L)
        NSFileManager.defaultManager.removeItemAtPath(folderPath, error = null)
        val second = f.scanner.scan(f.sourceId)

        assertEquals(1, f.fileDao.rows.size, "an unreadable folder must not evict already-ingested books")
        assertEquals(1, f.fileFolderDao.rows.size)
        assertEquals(1, f.libraryItemDao.itemsFor("lib-1").size, "the library row must survive too")
        assertEquals(0, second.removed, "a failed walk must suppress the stale sweep")
        assertEquals(1, second.failures.size, "an unlistable folder must surface as a scan failure")
    }

    @Test
    fun `an ingest failure suppresses the stale sweep for this pass`() = runTest {
        val f = fixture(fileDao = UpsertFailingFileDao(failingDisplayName = "bad.epub"))
        val folderPath = newFolder()
        writeFile(folderPath, "good.epub", epubBytes(paddingByte = 7))
        f.configureFolder(folderPath, libraryId = "lib-1")
        assertEquals(1, f.scanner.scan(f.sourceId).added)

        f.clock.advance(1_000L)
        NSFileManager.defaultManager.removeItemAtPath("$folderPath/good.epub", error = null)
        writeFile(folderPath, "bad.epub", epubBytes(paddingByte = 8))
        val second = f.scanner.scan(f.sourceId)

        assertEquals(1, second.failures.size)
        assertEquals(0, second.removed, "a failed ingest must suppress the stale sweep")
        assertEquals(1, f.fileDao.rows.size, "the previously-ingested book must survive a partial scan")
    }

    @Test
    fun `the same file in two folders is one row with two memberships`() = runTest {
        val f = fixture()
        val folderA = newFolder()
        val folderB = newFolder()
        val bytes = epubBytes(paddingByte = 9)
        writeFile(folderA, "shared.epub", bytes)
        writeFile(folderB, "shared.epub", bytes)
        f.configureFolder(folderA, libraryId = "lib-a", addedAtEpochMs = 0L)
        f.configureFolder(folderB, libraryId = "lib-b", addedAtEpochMs = 1L)

        val first = f.scanner.scan(f.sourceId)

        assertEquals(1, first.added, "identical content hashes to one file row")
        assertEquals(1, first.refreshed, "the second folder's walk touches the same row again")
        assertEquals(1, f.fileDao.rows.size)
        assertEquals(2, f.fileFolderDao.rows.size, "both folder memberships must be recorded")
        assertEquals(setOf(folderA, folderB), f.fileFolderDao.rows.values.map { it.folderTreeUri }.toSet())

        f.clock.advance(1_000L)
        NSFileManager.defaultManager.removeItemAtPath("$folderA/shared.epub", error = null)
        val second = f.scanner.scan(f.sourceId)

        assertEquals(0, second.removed, "the book still lives in the other folder")
        assertEquals(1, f.fileDao.rows.size)
        assertEquals(folderB, f.fileFolderDao.rows.values.single().folderTreeUri)

        f.clock.advance(1_000L)
        NSFileManager.defaultManager.removeItemAtPath("$folderB/shared.epub", error = null)
        val third = f.scanner.scan(f.sourceId)

        assertEquals(1, third.removed)
        assertTrue(f.fileDao.rows.isEmpty())
        assertTrue(f.fileFolderDao.rows.isEmpty())
    }

    @Test
    fun `losing the last membership reclaims the copied bytes`() = runTest {
        val f = fixture()
        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 13))
        f.configureFolder(folderPath, libraryId = "lib-1")
        f.scanner.scan(f.sourceId)
        val copiedPath = f.fileDao.rows.values.single().copiedPath
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(copiedPath), "precondition: bytes were copied in")

        f.clock.advance(1_000L)
        NSFileManager.defaultManager.removeItemAtPath("$folderPath/book.epub", error = null)

        assertEquals(1, f.scanner.scan(f.sourceId).removed)
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(copiedPath),
            "evicting the last membership must reclaim the copied book on disk",
        )
    }

    /** Fails the file-row write for one named file so the scanner's ingest-failure path runs. */
    private class UpsertFailingFileDao(private val failingDisplayName: String) : InMemoryLocalFilesFileDao() {
        override suspend fun upsert(entity: LocalFilesFileEntity) {
            if (entity.displayName == failingDisplayName) error("simulated write failure")
            super.upsert(entity)
        }
    }
}
