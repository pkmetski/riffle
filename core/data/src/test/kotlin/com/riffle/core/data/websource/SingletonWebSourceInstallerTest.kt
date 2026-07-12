package com.riffle.core.data.websource

import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.ChitankaWebSourceDescriptor
import com.riffle.core.domain.GutenbergWebSourceDescriptor
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.WebSourceRegistry
import com.riffle.core.logging.NoopLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [SingletonWebSourceInstaller] end-to-end for every singleton descriptor. Replaces the
 * per-source `ChitankaSourceInstallerTest` and `GutenbergSourceInstallerTest` (deleted with the
 * per-source installers themselves — ADR 0044 Phase 4). New singleton sources need no new test
 * class — the parameterised assertions here cover them once they're registered in
 * `WebSourceDescriptors`.
 */
class SingletonWebSourceInstallerTest {

    private fun installer(sourceDao: SourceDao, libraryDao: LibraryDao) =
        SingletonWebSourceInstaller(
            sourceDao = sourceDao,
            libraryDao = libraryDao,
            registry = WebSourceRegistry(WebSourceDescriptors.all),
            logger = NoopLogger,
        )

    @Test
    fun `install creates a fresh Chitanka source with expected libraries`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val id = installer(sourceDao, libraryDao).install(SourceType.CHITANKA)

        val row = sourceDao.getById(id)
        assertNotNull("Chitanka source row missing", row)
        assertEquals(SourceType.CHITANKA.name, row!!.type)
        assertEquals(ChitankaWebSourceDescriptor.urlPlaceholder, row.url)
        assertEquals("", row.username)
        // First install wins the active flag when no other active source exists.
        assertTrue(row.isActive)

        val libraries = libraryDao.forSource(id).map { it.id to it.name }
        assertTrue("Chitanka Books library missing", libraries.any { it.first == "books" && it.second == "Chitanka" })
        assertTrue("Gramofonche library missing", libraries.any { it.first == "audiobooks" && it.second == "Gramofonche" })
    }

    @Test
    fun `install creates a fresh Gutenberg source with expected library`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val id = installer(sourceDao, libraryDao).install(SourceType.GUTENBERG)

        val row = sourceDao.getById(id)
        assertNotNull("Gutenberg source row missing", row)
        assertEquals(SourceType.GUTENBERG.name, row!!.type)
        assertEquals(GutenbergWebSourceDescriptor.urlPlaceholder, row.url)

        val libraries = libraryDao.forSource(id).map { it.id to it.name }
        assertEquals(1, libraries.size)
        assertTrue("Gutenberg Books library missing", libraries.any { it.first == "books" && it.second == "Project Gutenberg" })
    }

    @Test
    fun `install is idempotent — second call returns the same id`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val svc = installer(sourceDao, libraryDao)

        val first = svc.install(SourceType.CHITANKA)
        val second = svc.install(SourceType.CHITANKA)

        assertEquals(first, second)
        assertEquals("only one CHITANKA row must exist", 1, sourceDao.rowsOfType(SourceType.CHITANKA.name))
    }

    @Test
    fun `install self-heals missing library rows on second call`() = runTest {
        val sourceDao = InMemorySourceDao()
        val libraryDao = InMemoryLibraryDao()
        val svc = installer(sourceDao, libraryDao)

        val id = svc.install(SourceType.CHITANKA)
        libraryDao.clear(id)          // simulate out-of-band drawer damage
        assertTrue(libraryDao.forSource(id).isEmpty())

        svc.install(SourceType.CHITANKA)
        val healed = libraryDao.forSource(id).map { it.id }.toSet()
        assertEquals(setOf("books", "audiobooks"), healed)
    }

    @Test
    fun `install refuses non-singleton descriptor`() = runTest {
        try {
            installer(InMemorySourceDao(), InMemoryLibraryDao()).install(SourceType.ABS)
            assert(false) { "expected require() failure — ABS is not a singleton" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("isSingleton") == true)
        }
    }

    // Guards the (deliberately duplicated) library-id strings on descriptor default lists — they
    // must match the catalog's ROOT_* constants so installed rows point at the right catalog
    // branch after a restart.
    @Test
    fun `descriptor library ids match catalog ROOT constants`() {
        val chi = ChitankaWebSourceDescriptor.defaultLibraries.map { it.id }.toSet()
        assertTrue(ChitankaCatalog.ROOT_BOOKS in chi)
        assertTrue(ChitankaCatalog.ROOT_AUDIOBOOKS in chi)
        val gb = GutenbergWebSourceDescriptor.defaultLibraries.map { it.id }.toSet()
        assertTrue(GutenbergCatalog.ROOT_BOOKS in gb)
    }

    // region in-memory DAOs (copied from LocalFilesSourceInstallerTest; SourceDao/LibraryDao
    // Room impls are androidTest-only, so the JVM test needs a hand-rolled fake).

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
        private val rows = mutableListOf<LibraryEntity>()

        fun forSource(sourceId: String): List<LibraryEntity> = rows.filter { it.sourceId == sourceId }
        fun clear(sourceId: String) { rows.removeAll { it.sourceId == sourceId } }

        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
            flowOf(forSource(sourceId))

        override suspend fun libraryIdsForSource(sourceId: String): List<String> =
            forSource(sourceId).map { it.id }

        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
            rows.firstOrNull { it.sourceId == sourceId && it.id == libraryId }

        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            libraries.forEach { entity ->
                val i = rows.indexOfFirst { it.sourceId == entity.sourceId && it.id == entity.id }
                if (i >= 0) rows[i] = entity else rows.add(entity)
            }
        }

        override suspend fun deleteBySourceId(sourceId: String) {
            rows.removeAll { it.sourceId == sourceId }
        }

        override suspend fun deleteById(sourceId: String, libraryId: String) {
            rows.removeAll { it.sourceId == sourceId && it.id == libraryId }
        }

        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
            // no-op; unused by installer
        }
    }

    // endregion
}
