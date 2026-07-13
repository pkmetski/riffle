package com.riffle.core.data.websource

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
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
 * Pins the contract for on-demand insertion of a browsed web-source [CatalogItem] into
 * `library_items`. This upserter is shared by every unbounded-catalogue Source (Chitanka,
 * Gutenberg, and any future entry) — refreshLibraryItems does NOT populate library_items for
 * them (ADR 0042), so the reader / audiobook player can only resolve an item once this
 * upserter has run.
 *
 * Chitanka fixtures are used here because they're the most representative — the same code
 * path runs for every other web source without change; that's the whole point of extracting
 * the class. The Recently-Added sentinel behaviour is the invariant every web source relies on.
 */
class WebSourceLibraryItemUpserterTest {

    private fun catalogEpub(id: String = "text/12345-x") = CatalogItem(
        id = id,
        rootId = ChitankaCatalog.ROOT_BOOKS,
        title = "Под игото",
        author = "Иван Вазов",
        coverUrl = "https://example.info/cover.jpg",
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
        val upserter = WebSourceLibraryItemUpserter(dao)

        upserter.upsert(sourceId = "src-1", item = catalogEpub())

        val row = dao.getById("src-1", "text/12345-x")
        assertNotNull("row must exist", row)
        assertEquals("src-1", row!!.sourceId)
        assertEquals("text/12345-x", row.id)
        assertEquals(ChitankaCatalog.ROOT_BOOKS, row.libraryId)
        assertEquals("Под игото", row.title)
        assertEquals("Иван Вазов", row.author)
        assertEquals("https://example.info/cover.jpg", row.coverUrl)
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
        val upserter = WebSourceLibraryItemUpserter(dao)

        upserter.upsert(sourceId = "src-1", item = catalogAudio())

        val row = dao.getById("src-1", "prikazki/1-slug")
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
        val upserter = WebSourceLibraryItemUpserter(dao)
        val item = catalogEpub()

        upserter.upsert("src-1", item)
        // Simulate reader progress persisted after the first open.
        dao.updateReadingProgress("src-1", item.id, 0.42f)

        upserter.upsert("src-1", item)

        val row = dao.getById("src-1", item.id)!!
        assertEquals("second upsert must not overwrite locally-tracked progress", 0.42f, row.readingProgress, 0.0001f)
    }

    @Test
    fun `browse-tap stamps addedAt = 0 sentinel so the item stays out of Recently Added`() = runTest {
        // A web-source browse tap upserts a row so the reader / audiobook player can resolve
        // the item, but that is not "added to the library" — writing a real clock timestamp
        // here would surface the item in the Recently Added rail immediately after browsing.
        // The sentinel is promoted to a real timestamp by LibraryItemDao.updateLastOpenedAt on
        // the first reader open (see LibraryItemDao test coverage). Flipping this back to
        // clock.nowMs() flips this test red. This invariant is shared across every web source.
        val dao = InMemoryLibraryItemDao()
        val upserter = WebSourceLibraryItemUpserter(dao)

        upserter.upsert(sourceId = "src-1", item = catalogEpub().copy(addedAt = null))

        assertEquals(0L, dao.getById("src-1", "text/12345-x")!!.addedAt)
    }

    @Test
    fun `browse-tap stamps sentinel even when CatalogItem carries an addedAt from remote payload`() = runTest {
        // Some catalogues (e.g. an ABS-shaped remote) might populate `addedAt` on CatalogItem.
        // The upserter must still write the sentinel — "the remote says it was added on date X"
        // is not the same as "the user added it to their library on date X".
        val dao = InMemoryLibraryItemDao()
        val upserter = WebSourceLibraryItemUpserter(dao)

        upserter.upsert(sourceId = "src-1", item = catalogEpub().copy(addedAt = 12_345L))

        assertEquals(0L, dao.getById("src-1", "text/12345-x")!!.addedAt)
    }

    @Test
    fun `re-tap after reader-open preserves the promoted addedAt`() = runTest {
        // Regression: updateMetadata is a Room @Update that copies every column of
        // LibraryItemMetadata onto the existing row, INCLUDING addedAt. Without the "read the
        // current addedAt and preserve it" step in the upserter, a second browse-tap on a book
        // whose sentinel had been promoted (by updateLastOpenedAt on the first reader open)
        // would overwrite the promoted timestamp with 0 and silently evict the book from
        // Recently Added. Simulating updateLastOpenedAt via updateMetadata here keeps the fake
        // dao minimal; the DAO-level test in LibraryItemDaoTest covers the actual promotion SQL.
        val dao = InMemoryLibraryItemDao()
        val upserter = WebSourceLibraryItemUpserter(dao)
        val item = catalogEpub()

        upserter.upsert("src-1", item)
        // Simulate updateLastOpenedAt promoting the sentinel to a real timestamp.
        val opened = dao.getById("src-1", item.id)!!.copy(addedAt = 9_000L, lastOpenedAt = 9_000L)
        dao.upsertAll(listOf(opened))

        upserter.upsert("src-1", item)

        assertEquals(
            "re-tap must not demote the promoted addedAt back to sentinel",
            9_000L,
            dao.getById("src-1", item.id)!!.addedAt,
        )
    }

    @Test
    fun `re-tap preserves promoted lastOpenedAt and finishedAt`() = runTest {
        // Regression: `updateMetadata` is a Room `@Update` that copies every column of
        // `LibraryItemMetadata` — including `lastOpenedAt` and `finishedAt` — onto the existing
        // row. A CatalogItem carries neither, so its `toEntity()` defaults both to null; without
        // the "read the current row and preserve the surviving locals" step in the upserter, a
        // second browse-tap after the reader has stamped either field would silently null it and
        // erase the book from Recently Opened / undo the "finished" mark. The ADR-0043 24 h TTL
        // cycle makes this a routine trigger: user opens the book, revisits the source next day,
        // taps the same title from the browse listing → gate refetches → this upserter runs.
        val dao = InMemoryLibraryItemDao()
        val upserter = WebSourceLibraryItemUpserter(dao)
        val item = catalogEpub()

        upserter.upsert("src-1", item)
        // Simulate the reader path stamping both timestamps.
        val opened = dao.getById("src-1", item.id)!!
            .copy(addedAt = 9_000L, lastOpenedAt = 12_345L, finishedAt = 34_567L)
        dao.upsertAll(listOf(opened))

        upserter.upsert("src-1", item)

        val row = dao.getById("src-1", item.id)!!
        assertEquals("re-tap must not null the promoted lastOpenedAt", 12_345L, row.lastOpenedAt)
        assertEquals("re-tap must not undo the finished stamp", 34_567L, row.finishedAt)
    }

    @Test
    fun `null description and coverUrl are handled (null cover coerced to empty)`() = runTest {
        val dao = InMemoryLibraryItemDao()
        val upserter = WebSourceLibraryItemUpserter(dao)

        upserter.upsert(
            sourceId = "src-1",
            item = catalogEpub().copy(coverUrl = null, description = null),
        )

        val row = dao.getById("src-1", "text/12345-x")!!
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

        override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) {
            rows.entries.removeAll { it.value.sourceId == sourceId && it.value.id in itemIds }
        }

        override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> =
            rows.values.filter { it.sourceId == sourceId && it.libraryId == libraryId }.map { it.id }

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
        override suspend fun updateInitialReadingProgress(sourceId: String, itemId: String, progress: Float) {
            val row = rows[sourceId to itemId] ?: return
            if (row.readingProgress == 0f && row.lastOpenedAt == null) {
                rows[sourceId to itemId] = row.copy(readingProgress = progress)
            }
        }
        override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) { }
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) { }
        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
        override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = emptyList()
    }
}
