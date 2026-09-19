package com.riffle.core.data.localfiles

import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.TestClock
import com.riffle.core.models.SourceType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS counterpart to `LocalFilesSourceInstallerTest` (androidHostTest). Android's installer is
 * `LocalFilesSourceInstaller`; iOS runs its own [IosLocalFilesSourceInstaller], so the Android
 * assertions prove nothing here — the singleton/active-flag rules have to be pinned against the
 * iOS class itself.
 *
 * The `installFolder*` cases go further than the Android suite and drive the whole iOS chain —
 * installer → [IosLocalFilesFolderRepository] → [IosLocalFilesScanner] → [IosCopyInService] — over
 * real files, which is the path `SourceOnboardingHost` takes when a user picks a folder on iOS.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalFilesSourceInstallerTest {

    private val dispatchers = IosDispatcherProvider
    private val tempDirs = mutableListOf<String>()
    private val installedSourceIds = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        val manager = NSFileManager.defaultManager
        tempDirs.forEach { manager.removeItemAtPath(it, error = null) }
        installedSourceIds.forEach {
            manager.removeItemAtPath(documentsDirectory() + "/localfiles/" + it, error = null)
        }
    }

    @Test
    fun `ensureLocalFilesSource creates a LOCAL_FILES source on the first call`() = runTest {
        val sourceDao = InMemorySourceDao()
        val installer = installer(sourceDao)

        val id = installer.ensureLocalFilesSource()

        val row = sourceDao.getById(id)
        assertNotNull(row, "source row missing after ensureLocalFilesSource")
        assertEquals(SourceType.LOCAL_FILES.name, row.type)
        assertEquals(IosLocalFilesSourceInstaller.LOCAL_FILES_URL_PLACEHOLDER, row.url)
        assertEquals("", row.username)
        assertTrue(row.isActive, "the first-installed source becomes active because none was")
    }

    @Test
    fun `ensureLocalFilesSource is idempotent and returns the same id`() = runTest {
        val sourceDao = InMemorySourceDao()
        val installer = installer(sourceDao)

        val first = installer.ensureLocalFilesSource()
        val second = installer.ensureLocalFilesSource()

        assertEquals(first, second)
        assertEquals(
            1,
            sourceDao.rowsOfType(SourceType.LOCAL_FILES.name),
            "LocalFiles is a device singleton — a second row would give the registry two factories",
        )
    }

    @Test
    fun `ensureLocalFilesSource does not become active when another source already is`() = runTest {
        val sourceDao = InMemorySourceDao()
        sourceDao.upsert(
            SourceEntity(
                id = "abs-1",
                url = "https://abs.example.com",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "user",
                // Literal rather than the enum: `ServerType` is a retired identifier under the
                // Source/Service taxonomy (ADR 0049) and `checkNoServerReferences` rejects new
                // uses of it. The Android counterpart spells it the same way.
                serverType = "AUDIOBOOKSHELF",
                type = "ABS",
            ),
        )
        val installer = installer(sourceDao)

        val id = installer.ensureLocalFilesSource()

        assertEquals(false, sourceDao.getById(id)!!.isActive)
        assertEquals(true, sourceDao.getById("abs-1")!!.isActive, "the existing active source keeps the flag")
    }

    @Test
    fun `installFolderDetailed installs the source registers the library and ingests the books`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val libraryItemDao = FakeLibraryItemDao()
        val installer = installer(sourceDao, libraryDao, libraryItemDao)
        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 21))

        val result = installer.installFolderDetailed(FolderUri(folderPath))
        installedSourceIds += result.sourceId

        assertEquals(SourceType.LOCAL_FILES.name, sourceDao.getById(result.sourceId)!!.type)
        assertTrue(
            result.libraryId.startsWith(IosLocalFilesFolderRepository.LOCAL_FILES_LIBRARY_ID_PREFIX),
            "each folder gets its own folder-library id, got ${result.libraryId}",
        )
        assertEquals(listOf(result.libraryId), libraryDao.libraryIdsForSource(result.sourceId))
        assertEquals(1, result.scan.added)
        assertEquals(emptyList(), result.scan.failures)
        assertEquals(result.libraryId, libraryItemDao.upserted.single().libraryId)
    }

    @Test
    fun `installFolder reports what the scan added and what it failed on`() = runTest {
        val installer = installer(InMemorySourceDao())
        val folderPath = newFolder()
        writeFile(folderPath, "book.epub", epubBytes(paddingByte = 22))
        writeFile(folderPath, "notes.txt", "not a book".encodeToByteArray())

        val report = installer.installFolder(FolderUri(folderPath))
        installedSourceIds += installer.ensureLocalFilesSource()

        assertEquals(1, report.added, "the epub is ingested and the txt is skipped, not failed")
        assertEquals(0, report.failures)
    }

    // region helpers

    private fun installer(
        sourceDao: SourceDao,
        libraryDao: InMemoryLibraryDao = InMemoryLibraryDao(),
        libraryItemDao: FakeLibraryItemDao = FakeLibraryItemDao(),
    ): IosLocalFilesSourceInstaller {
        val folderDao = InMemoryLocalFilesFolderDao()
        val fileDao = InMemoryLocalFilesFileDao()
        val fileFolderDao = InMemoryLocalFilesFileFolderDao(fileDao)
        val clock = TestClock(1_000L)
        return IosLocalFilesSourceInstaller(
            sourceDao = sourceDao,
            folderRepository = IosLocalFilesFolderRepository(folderDao, libraryDao, fileFolderDao, clock),
            scanner = IosLocalFilesScanner(
                folderDao,
                fileDao,
                fileFolderDao,
                libraryItemDao,
                IosFolderWalker(dispatchers),
                IosCopyInService(dispatchers),
                dispatchers,
                clock,
            ),
        )
    }

    private fun documentsDirectory(): String {
        @Suppress("UNCHECKED_CAST")
        return (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true) as List<String>)
            .first()
    }

    private fun newFolder(): String {
        val path = NSTemporaryDirectory() + "riffle-installer-test-" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
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

    private fun epubBytes(paddingByte: Byte): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(32) { paddingByte }

    // endregion

    private class InMemorySourceDao : SourceDao {
        private val rows = mutableMapOf<String, SourceEntity>()

        fun rowsOfType(type: String): Int = rows.values.count { it.type == type }

        override fun observeAll(): Flow<List<SourceEntity>> = flowOf(rows.values.toList())
        override suspend fun getActive(): SourceEntity? = rows.values.firstOrNull { it.isActive }
        override suspend fun upsert(source: SourceEntity) {
            rows[source.id] = source
        }
        override suspend fun clearActiveFlag() {
            for ((id, entity) in rows.toMap()) rows[id] = entity.copy(isActive = false)
        }
        override suspend fun setActive(id: String) {
            rows[id]?.let { rows[id] = it.copy(isActive = true) }
        }
        override suspend fun getById(id: String): SourceEntity? = rows[id]
        override suspend fun getByType(type: String): SourceEntity? = rows.values.firstOrNull { it.type == type }
        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }
        override suspend fun deleteReadaloudLinksForSource(id: String) = Unit
        override suspend fun deleteReadaloudCandidatesForSource(id: String) = Unit
        override suspend fun deleteReadaloudDismissalsForSource(id: String) = Unit
        override suspend fun deleteSeriesForSource(id: String) = Unit
        override suspend fun deleteSeriesItemsForSource(id: String) = Unit
        override suspend fun deleteCollectionsForSource(id: String) = Unit
        override suspend fun deleteCollectionItemsForSource(id: String) = Unit
        override suspend fun deletePlaylistItemsForSource(id: String) = Unit
        override suspend fun deletePlaylistsForSource(id: String) = Unit
        override suspend fun deleteReadingPositionsForSource(id: String) = Unit
        override suspend fun deleteBookFormattingPreferencesForSource(id: String) = Unit
        override suspend fun deleteAnnotationsForSource(id: String) = Unit
        override suspend fun deleteReadaloudResumePositionsForSource(id: String) = Unit
        override suspend fun deleteAudioPlaybackPreferencesForSource(id: String) = Unit
        override suspend fun deleteAudiobookPositionsForSource(id: String) = Unit
        override suspend fun deleteAudiobookBookmarksForSource(id: String) = Unit
        override suspend fun deleteTocCacheForSource(id: String) = Unit
        override suspend fun deleteAudiobookChapterCacheForSource(id: String) = Unit
        override suspend fun deleteLocalFilesFileFoldersForSource(id: String) = Unit
        override suspend fun deleteLocalFilesFilesForSource(id: String) = Unit
        override suspend fun deleteLocalFilesFoldersForSource(id: String) = Unit
        override suspend fun deleteLocalFileMetadataOverridesForSource(id: String) = Unit
        override suspend fun deleteRemoteItemFreshnessForSource(id: String) = Unit
        override suspend fun deletePublicationMetricsCacheForSource(id: String) = Unit
        override suspend fun deleteLibraryItemsForSource(id: String) = Unit
        override suspend fun deleteLibrariesForSource(id: String) = Unit
        override suspend fun setAbsUserId(id: String, absUserId: String) {
            rows[id]?.let { rows[id] = it.copy(absUserId = absUserId) }
        }
    }
}
