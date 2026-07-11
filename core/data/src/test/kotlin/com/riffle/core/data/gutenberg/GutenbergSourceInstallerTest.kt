package com.riffle.core.data.gutenberg

import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.SourceType
import com.riffle.core.logging.NoopLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the singleton-per-device Gutenberg installer. Mirrors the shape of
 * [com.riffle.core.data.chitanka.ChitankaSourceInstallerTest].
 */
class GutenbergSourceInstallerTest {

    @Test
    fun `install creates a GUTENBERG source row on first call`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val installer = GutenbergSourceInstaller(sourceDao, libraryDao, NoopLogger)

        val id = installer.install()

        val row = sourceDao.getById(id)
        assertNotNull("Source row missing after install", row)
        assertEquals(SourceType.GUTENBERG.name, row!!.type)
        assertEquals(GutenbergSourceInstaller.GUTENBERG_URL_PLACEHOLDER, row.url)
        assertEquals("", row.username)
        assertTrue(row.isActive)
    }

    @Test
    fun `install is idempotent - second call returns the same id and does not duplicate libraries`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val installer = GutenbergSourceInstaller(sourceDao, libraryDao, NoopLogger)

        val first = installer.install()
        val second = installer.install()

        assertEquals(first, second)
        assertEquals(1, sourceDao.rowsOfType(SourceType.GUTENBERG.name))
        // Gutenberg installs a single Library row (Books).
        assertEquals(1, libraryDao.forSource(first).size)
    }

    @Test
    fun `install seeds the single Books library with the expected id`() = runTest {
        val libraryDao = InMemoryLibraryDao()
        val installer = GutenbergSourceInstaller(InMemorySourceDao(), libraryDao, NoopLogger)

        val sourceId = installer.install()

        val libs = libraryDao.forSource(sourceId)
        assertEquals(1, libs.size)
        val books = libs.single()
        assertEquals(GutenbergCatalog.ROOT_BOOKS, books.id)
        assertEquals("book", books.mediaType)
    }

    @Test
    fun `install does not become active when another source is already active`() = runTest {
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
        val installer = GutenbergSourceInstaller(sourceDao, InMemoryLibraryDao(), NoopLogger)

        val id = installer.install()

        val gb = sourceDao.getById(id)!!
        assertFalse(gb.isActive)
        assertTrue(sourceDao.getById("abs-1")!!.isActive)
    }

    // region in-memory DAOs (duplicated from ChitankaSourceInstallerTest)

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
        override suspend fun deleteById(sourceId: String, libraryId: String) {
            rows.removeIf { it.sourceId == sourceId && it.id == libraryId }
        }
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
            val idx = rows.indexOfFirst { it.sourceId == sourceId && it.id == libraryId }
            if (idx >= 0) rows[idx] = rows[idx].copy(isUnsupported = isUnsupported)
        }
    }

    // endregion
}
