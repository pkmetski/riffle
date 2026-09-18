package com.riffle.core.data.localfiles

import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.TestClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @AfterTest
    fun cleanup() {
        val manager = NSFileManager.defaultManager
        tempDirs.forEach { manager.removeItemAtPath(it, error = null) }
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
        assertEquals("epub", libraryItemDao.upserted.single().ebookFormat)
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

    private class InMemoryLocalFilesFolderDao : LocalFilesFolderDao {
        private val store = mutableMapOf<Pair<String, String>, LocalFilesFolderEntity>()
        override suspend fun upsert(entity: LocalFilesFolderEntity) {
            store[entity.sourceId to entity.treeUri] = entity
        }
        override suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity> =
            store.values.filter { it.sourceId == sourceId }.sortedBy { it.addedAtEpochMs }
        override fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>> =
            MutableStateFlow(store.values.filter { it.sourceId == sourceId })
        override suspend fun getByLibraryId(sourceId: String, libraryId: String): LocalFilesFolderEntity? =
            store.values.firstOrNull { it.sourceId == sourceId && it.libraryId == libraryId }
        override suspend fun delete(sourceId: String, treeUri: String) {
            store.remove(sourceId to treeUri)
        }
    }

    private class InMemoryLocalFilesFileDao : LocalFilesFileDao {
        val rows = mutableMapOf<Pair<String, String>, LocalFilesFileEntity>()
        override suspend fun upsert(entity: LocalFilesFileEntity) {
            rows[entity.sourceId to entity.sourceItemId] = entity
        }
        override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? =
            rows[sourceId to sourceItemId]
        override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> =
            rows.values.filter { it.sourceId == sourceId }
        override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFilesFileEntity> =
            rows.values.filter { it.sourceId == sourceId && it.sourceItemId in sourceItemIds }
        override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {
            rows[sourceId to sourceItemId]?.let { rows[sourceId to sourceItemId] = it.copy(lastSeenAtEpochMs = seenAt) }
        }
        override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) {
            rows[sourceId to sourceItemId]?.let { rows[sourceId to sourceItemId] = it.copy(displayName = displayName) }
        }
        override suspend fun delete(sourceId: String, sourceItemId: String) {
            rows.remove(sourceId to sourceItemId)
        }
    }

    private class InMemoryLocalFilesFileFolderDao(
        private val fileDao: InMemoryLocalFilesFileDao,
    ) : LocalFilesFileFolderDao {
        val rows = mutableMapOf<Triple<String, String, String>, LocalFilesFileFolderEntity>()
        override suspend fun upsert(entity: LocalFilesFileFolderEntity) {
            rows[Triple(entity.sourceId, entity.sourceItemId, entity.folderTreeUri)] = entity
        }
        override suspend fun forFile(sourceId: String, sourceItemId: String): List<LocalFilesFileFolderEntity> =
            rows.values.filter { it.sourceId == sourceId && it.sourceItemId == sourceItemId }
        override suspend fun forFolder(sourceId: String, folderTreeUri: String): List<LocalFilesFileFolderEntity> =
            rows.values.filter { it.sourceId == sourceId && it.folderTreeUri == folderTreeUri }
        override suspend fun itemIdsInFolder(sourceId: String, folderTreeUri: String): List<String> =
            forFolder(sourceId, folderTreeUri).map { it.sourceItemId }
        override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileFolderEntity> =
            rows.values.filter { it.sourceId == sourceId && it.lastSeenAtEpochMs < scanStart }
        override suspend fun delete(sourceId: String, sourceItemId: String, folderTreeUri: String) {
            rows.remove(Triple(sourceId, sourceItemId, folderTreeUri))
        }
        override suspend fun deleteFolder(sourceId: String, folderTreeUri: String) {
            rows.keys.filter { it.first == sourceId && it.third == folderTreeUri }.forEach { rows.remove(it) }
        }
        override suspend fun deleteFile(sourceId: String, sourceItemId: String) {
            rows.keys.filter { it.first == sourceId && it.second == sourceItemId }.forEach { rows.remove(it) }
        }
        override suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity> {
            val liveItemIds = rows.values.filter { it.sourceId == sourceId }.map { it.sourceItemId }.toHashSet()
            return fileDao.rows.values.filter { it.sourceId == sourceId && it.sourceItemId !in liveItemIds }
        }
    }

    private class InMemoryLibraryDao : LibraryDao {
        private val store = mutableMapOf<Pair<String, String>, LibraryEntity>()
        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
            MutableStateFlow(store.values.filter { it.sourceId == sourceId })
        override suspend fun libraryIdsForSource(sourceId: String): List<String> =
            store.values.filter { it.sourceId == sourceId }.map { it.id }
        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
            store[sourceId to libraryId]
        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            libraries.forEach { store[it.sourceId to it.id] = it }
        }
        override suspend fun deleteBySourceId(sourceId: String) {
            store.keys.filter { it.first == sourceId }.forEach { store.remove(it) }
        }
        override suspend fun deleteById(sourceId: String, libraryId: String) {
            store.remove(sourceId to libraryId)
        }
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
            store[sourceId to libraryId]?.let { store[sourceId to libraryId] = it.copy(isUnsupported = isUnsupported) }
        }
    }
}
