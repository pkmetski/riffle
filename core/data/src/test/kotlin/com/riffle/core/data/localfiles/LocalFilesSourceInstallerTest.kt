package com.riffle.core.data.localfiles

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.SourceType
import com.riffle.core.logging.NoopLogger
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFilesSourceInstallerTest {

    @Test
    fun `ensureLocalFilesSource creates a fresh LOCAL_FILES source on first call`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val installer = installer(sourceDao = sourceDao, libraryDao = libraryDao)

        val id = installer.ensureLocalFilesSource()

        val row = sourceDao.getById(id)
        assertNotNull("Source row missing after ensureLocalFilesSource", row)
        assertEquals(SourceType.LOCAL_FILES.name, row!!.type)
        assertEquals(LocalFilesSourceInstaller.LOCAL_FILES_URL_PLACEHOLDER, row.url)
        assertEquals("", row.username)
        // The first-installed source becomes active because no other active source existed.
        assertTrue(row.isActive)
    }

    @Test
    fun `ensureLocalFilesSource is idempotent - second call returns the same id`() = runTest {
        val sourceDao = InMemorySourceDao()
        val installer = installer(sourceDao = sourceDao)

        val first = installer.ensureLocalFilesSource()
        val second = installer.ensureLocalFilesSource()

        assertEquals(first, second)
        // Only one row survives — critical: any second insertion would give the CatalogRegistry
        // two factories to disambiguate, and multi-folder-in-one-source is the intended model.
        assertEquals(1, sourceDao.rowsOfType(SourceType.LOCAL_FILES.name))
    }

    @Test
    fun `ensureLocalFilesSource seeds the synthetic local root library row`() = runTest {
        val libraryDao = InMemoryLibraryDao()
        val installer = installer(libraryDao = libraryDao)

        val sourceId = installer.ensureLocalFilesSource()

        val libs = libraryDao.forSource(sourceId)
        assertEquals(1, libs.size)
        val lib = libs.single()
        assertEquals(LocalFilesCatalog.LOCAL_ROOT_ID, lib.id)
        assertEquals(sourceId, lib.sourceId)
        assertEquals("book", lib.mediaType)
    }

    @Test
    fun `ensureLocalFilesSource does not become active when another source is already active`() = runTest {
        val sourceDao = InMemorySourceDao()
        sourceDao.upsert(
            SourceEntity(
                id = "abs-1",
                url = "https://abs.example.com",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "user",
                serverType = "AUDIOBOOKSHELF",
                type = "ABS",
            ),
        )
        val installer = installer(sourceDao = sourceDao)

        val id = installer.ensureLocalFilesSource()

        val local = sourceDao.getById(id)!!
        // Adding LocalFiles when an ABS source is already active must not knock it out of the
        // active slot — first-source-active is a convenience, not a takeover.
        assertEquals(false, local.isActive)
        assertEquals(true, sourceDao.getById("abs-1")!!.isActive)
    }

    // region helpers

    private fun installer(
        sourceDao: SourceDao = InMemorySourceDao(),
        libraryDao: LibraryDao = InMemoryLibraryDao(),
    ): LocalFilesSourceInstaller = LocalFilesSourceInstaller(
        sourceDao = sourceDao,
        libraryDao = libraryDao,
        // folderRepository + scanner are only exercised by installFolder; ensureLocalFilesSource
        // touches neither. Relaxed mocks keep the constructor happy without needing an Android
        // Context or the metadata-extractor chain.
        folderRepository = mockk(relaxed = true),
        scanner = mockk(relaxed = true),
        logger = NoopLogger,
    )

    // endregion

    // region in-memory DAOs

    private class InMemorySourceDao : SourceDao {
        val rows = mutableMapOf<String, SourceEntity>()
        fun rowsOfType(type: String): Int = rows.values.count { it.type == type }

        override fun observeAll(): Flow<List<SourceEntity>> = flowOf(rows.values.toList())
        override suspend fun getActive(): SourceEntity? = rows.values.firstOrNull { it.isActive }
        override suspend fun upsert(source: SourceEntity) { rows[source.id] = source }
        override suspend fun clearActiveFlag() {
            for ((id, entity) in rows.toMap()) rows[id] = entity.copy(isActive = false)
        }
        override suspend fun setActive(id: String) {
            rows[id]?.let { rows[id] = it.copy(isActive = true) }
        }
        override suspend fun setActiveAtomic(id: String) { clearActiveFlag(); setActive(id) }
        override suspend fun upsertAsFirstIfNoActive(source: SourceEntity): SourceEntity {
            val toInsert = source.copy(isActive = getActive() == null)
            upsert(toInsert)
            return toInsert
        }
        override suspend fun getById(id: String): SourceEntity? = rows[id]
        override suspend fun getByType(type: String): SourceEntity? =
            rows.values.firstOrNull { it.type == type }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun setAbsUserId(id: String, absUserId: String) {
            rows[id]?.let { rows[id] = it.copy(absUserId = absUserId) }
        }
    }

    private class InMemoryLibraryDao : LibraryDao {
        val rows = mutableListOf<LibraryEntity>()
        fun forSource(sourceId: String): List<LibraryEntity> = rows.filter { it.sourceId == sourceId }
        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
            MutableStateFlow(rows.filter { it.sourceId == sourceId })
        override suspend fun libraryIdsForSource(sourceId: String): List<String> =
            rows.filter { it.sourceId == sourceId }.map { it.id }
        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
            rows.firstOrNull { it.sourceId == sourceId && it.id == libraryId }
        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            for (lib in libraries) {
                rows.removeIf { it.sourceId == lib.sourceId && it.id == lib.id }
                rows.add(lib)
            }
        }
        override suspend fun deleteBySourceId(sourceId: String) {
            rows.removeIf { it.sourceId == sourceId }
        }
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
            val idx = rows.indexOfFirst { it.sourceId == sourceId && it.id == libraryId }
            if (idx >= 0) rows[idx] = rows[idx].copy(isUnsupported = isUnsupported)
        }
    }

    // endregion
}
