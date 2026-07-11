package com.riffle.core.data.chitanka

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.domain.EbookFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the contract for on-demand insertion of a browsed Chitanka [CatalogItem] into `library_items`.
 *
 * Chitanka does NOT populate library_items via refresh (ADR 0042: unbounded catalogue), so the
 * reader / audiobook player can only resolve an item once this upserter has run. The tests here
 * pin the field mapping (CatalogItem → LibraryItemEntity) and the re-open behaviour (existing row
 * keeps its readingProgress, mirroring [LibraryItemDao.replaceAllForLibrary]).
 */
class ChitankaLibraryItemUpserterTest {

    private fun catalogEpub(id: String = "text/12345-x") = CatalogItem(
        id = id,
        rootId = ChitankaCatalog.ROOT_BOOKS,
        title = "Под игото",
        author = "Иван Вазов",
        coverUrl = "https://chitanka.info/cover.jpg",
        ebookFormat = BookFormat.Epub,
        hasAudio = false,
        description = "desc",
        seriesName = "series-x",
        seriesSequence = "2",
        publishedYear = "1889",
        genres = listOf("роман", "класика"),
        language = "Bulgarian",
    )

    private fun catalogAudio(id: String = "prikazki/1-slug") = CatalogItem(
        id = id,
        rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
        title = "Три прасенца",
        author = "нар. приказка",
        coverUrl = null,
        ebookFormat = BookFormat.Audiobook,
        hasAudio = true,
        language = "Bulgarian",
    )

    @Test
    fun `first upsert inserts EPUB row with mapped fields`() = runTest {
        val dao = InMemoryLibraryItemDao()
        val upserter = ChitankaLibraryItemUpserter(dao, com.riffle.core.domain.TestClock(initialMs = 7_000L))

        upserter.upsert(sourceId = "chit-1", item = catalogEpub())

        val row = dao.getById("chit-1", "text/12345-x")
        assertNotNull("row must exist", row)
        assertEquals("chit-1", row!!.sourceId)
        assertEquals("text/12345-x", row.id)
        assertEquals(ChitankaCatalog.ROOT_BOOKS, row.libraryId)
        assertEquals("Под игото", row.title)
        assertEquals("Иван Вазов", row.author)
        assertEquals("https://chitanka.info/cover.jpg", row.coverUrl)
        assertEquals(0f, row.readingProgress, 0.0001f)
        assertEquals(EbookFormat.Epub.toStorageString(), row.ebookFormat)
        assertEquals(false, row.hasAudio)
        assertEquals("роман,класика", row.genres)
        assertEquals("series-x", row.seriesName)
        assertEquals("2", row.seriesSequence)
        assertEquals("1889", row.publishedYear)
        assertEquals("Bulgarian", row.language)
    }

    @Test
    fun `audiobook item maps hasAudio true and libraryId to audio root`() = runTest {
        val dao = InMemoryLibraryItemDao()
        val upserter = ChitankaLibraryItemUpserter(dao, com.riffle.core.domain.TestClock(initialMs = 7_000L))

        upserter.upsert(sourceId = "chit-1", item = catalogAudio())

        val row = dao.getById("chit-1", "prikazki/1-slug")
        assertNotNull(row)
        assertEquals(true, row!!.hasAudio)
        assertEquals(ChitankaCatalog.ROOT_AUDIOBOOKS, row.libraryId)
        // BookFormat.Audiobook maps to unsupported ebookFormat (the audiobook path is what runs).
        assertEquals(EbookFormat.Unsupported.toStorageString(), row.ebookFormat)
        assertEquals("", row.coverUrl)
    }

    @Test
    fun `re-open preserves existing readingProgress`() = runTest {
        val dao = InMemoryLibraryItemDao()
        val upserter = ChitankaLibraryItemUpserter(dao, com.riffle.core.domain.TestClock(initialMs = 7_000L))
        val item = catalogEpub()

        upserter.upsert("chit-1", item)
        // Simulate reader progress persisted after the first open.
        dao.updateReadingProgress("chit-1", item.id, 0.42f)

        upserter.upsert("chit-1", item)

        val row = dao.getById("chit-1", item.id)!!
        assertEquals("second upsert must not overwrite locally-tracked progress", 0.42f, row.readingProgress, 0.0001f)
    }

    @Test
    fun `addedAt falls back to clock when the CatalogItem has none`() = runTest {
        // Regression: Chitanka listings carry no addedAt, so without a fallback these rows land
        // with NULL addedAt and get silently ordered to the tail of Recently Added (or off the
        // top-50 entirely when the audiobooks library also holds ABS items). Pin the stamp so
        // reverting the fix flips this test red.
        val dao = InMemoryLibraryItemDao()
        val upserter = ChitankaLibraryItemUpserter(
            dao,
            com.riffle.core.domain.TestClock(initialMs = 12_345L),
        )

        upserter.upsert(sourceId = "chit-1", item = catalogEpub().copy(addedAt = null))

        assertEquals(12_345L, dao.getById("chit-1", "text/12345-x")!!.addedAt)
    }

    @Test
    fun `null description and coverUrl are handled (null cover coerced to empty)`() = runTest {
        val dao = InMemoryLibraryItemDao()
        val upserter = ChitankaLibraryItemUpserter(dao, com.riffle.core.domain.TestClock(initialMs = 7_000L))

        upserter.upsert(
            sourceId = "chit-1",
            item = catalogEpub().copy(coverUrl = null, description = null),
        )

        val row = dao.getById("chit-1", "text/12345-x")!!
        assertEquals("", row.coverUrl)
        assertNull(row.description)
    }

    // ---- Test double ------------------------------------------------------------------

    private class InMemoryLibraryItemDao : LibraryItemDao {
        private val rows = mutableMapOf<Pair<String, String>, LibraryItemEntity>()

        override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> = emptyList()
        override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())

        override suspend fun upsertAll(items: List<LibraryItemEntity>) {
            items.forEach { rows[it.sourceId to it.id] = it }
        }

        override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) {
            items.forEach { rows.putIfAbsent(it.sourceId to it.id, it) }
        }

        override suspend fun updateMetadata(metadata: LibraryItemMetadata) {
            val existing = rows[metadata.sourceId to metadata.id] ?: return
            rows[metadata.sourceId to metadata.id] = existing.copy(
                libraryId = metadata.libraryId,
                title = metadata.title,
                author = metadata.author,
                coverUrl = metadata.coverUrl,
                ebookFileIno = metadata.ebookFileIno,
                ebookFormat = metadata.ebookFormat,
                hasAudio = metadata.hasAudio,
                audioDurationSec = metadata.audioDurationSec,
                description = metadata.description,
                seriesName = metadata.seriesName,
                publishedYear = metadata.publishedYear,
                genres = metadata.genres,
                publisher = metadata.publisher,
                language = metadata.language,
                lastOpenedAt = metadata.lastOpenedAt,
                addedAt = metadata.addedAt,
                isbn = metadata.isbn,
                asin = metadata.asin,
                finishedAt = metadata.finishedAt,
                // readingProgress intentionally NOT copied — updateMetadata excludes it (Room @Update).
            )
        }

        override suspend fun deleteRemovedFromLibrary(sourceId: String, libraryId: String, serverItemIds: List<String>) {
            rows.entries.removeAll { it.value.libraryId == libraryId && it.value.sourceId == sourceId && it.value.id !in serverItemIds }
        }

        override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? = rows[sourceId to itemId]
        override suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity> =
            itemIds.mapNotNull { rows[sourceId to it] }

        override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> = flowOf(rows[sourceId to itemId])
        override suspend fun findSourceIdForItem(itemId: String): String? = rows.values.firstOrNull { it.id == itemId }?.sourceId
        override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) {
            rows.entries.removeAll { it.value.libraryId == libraryId && it.value.sourceId == sourceId }
        }

        override suspend fun deleteById(sourceId: String, itemId: String) { rows.remove(sourceId to itemId) }
        override fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) { }
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            val row = rows[sourceId to itemId] ?: return
            rows[sourceId to itemId] = row.copy(readingProgress = progress)
        }
        override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) { }
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) { }
        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
        override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = emptyList()
    }
}
