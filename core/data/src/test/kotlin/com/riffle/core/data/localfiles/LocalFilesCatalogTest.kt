package com.riffle.core.data.localfiles

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalFilesCatalogTest {

    private val sourceId = "src-1"
    private val libraryId = LocalFilesCatalog.LOCAL_ROOT_ID

    @Test
    fun `listRoots returns the single synthetic local root`() = runTest {
        val catalog = catalog()
        val roots = catalog.listRoots()
        assertEquals(1, roots.size)
        assertEquals(libraryId, roots[0].id)
        assertEquals("Local Files", roots[0].name)
        assertEquals(SourceType.LOCAL_FILES, catalog.sourceType)
    }

    @Test
    fun `browse returns items sorted by title, paged`() = runTest {
        val items = FakeLibraryItemDao()
        items.emit(
            sourceId,
            listOf(
                epubItem("c", "Cormac"),
                epubItem("a", "Alpha"),
                epubItem("b", "Bravo"),
            ),
        )
        val catalog = catalog(items = items)

        val page0 = catalog.browse(libraryId, SortKey.TITLE, page = 0, pageSize = 2)
        assertEquals(listOf("Alpha", "Bravo"), page0.map { it.title })

        val page1 = catalog.browse(libraryId, SortKey.TITLE, page = 1, pageSize = 2)
        assertEquals(listOf("Cormac"), page1.map { it.title })
    }

    @Test
    fun `browse sort by ADDED_AT is descending`() = runTest {
        val items = FakeLibraryItemDao()
        items.emit(
            sourceId,
            listOf(
                epubItem("old", "Old", addedAt = 100L),
                epubItem("new", "New", addedAt = 500L),
                epubItem("mid", "Mid", addedAt = 250L),
            ),
        )
        val catalog = catalog(items = items)
        val browsed = catalog.browse(libraryId, SortKey.ADDED_AT)
        assertEquals(listOf("New", "Mid", "Old"), browsed.map { it.title })
    }

    @Test
    fun `search matches title and author case-insensitively`() = runTest {
        val items = FakeLibraryItemDao()
        items.emit(
            sourceId,
            listOf(
                epubItem("1", "The Hobbit", author = "J.R.R. Tolkien"),
                epubItem("2", "Dune", author = "Frank Herbert"),
                epubItem("3", "Foundation", author = "Isaac Asimov"),
            ),
        )
        val catalog = catalog(items = items)

        assertEquals(listOf("The Hobbit"), catalog.search(libraryId, "hobbit").map { it.title })
        assertEquals(listOf("Foundation"), catalog.search(libraryId, "asimov").map { it.title })
        assertTrue(catalog.search(libraryId, "").isEmpty())
    }

    @Test
    fun `getItem returns null for missing item`() = runTest {
        val items = FakeLibraryItemDao().also { it.emit(sourceId, listOf(epubItem("x", "x"))) }
        val catalog = catalog(items = items)
        assertEquals("x", catalog.getItem("x")?.title)
        assertNull(catalog.getItem("missing"))
    }

    @Test
    fun `fetchFile returns Local handle from the copied path`() = runTest {
        val fileDao = InMemoryFileDao()
        val tmp = File.createTempFile("book", ".epub").apply { writeText("payload") }
        fileDao.rows[sourceId to "item-1"] = LocalFilesFileEntity(
            sourceId = sourceId,
            sourceItemId = "item-1",
            folderTreeUri = "content://tree/A",
            originalUri = "content://tree/A/document/1",
            copiedPath = tmp.absolutePath,
            coverPath = null,
            format = "epub",
            sizeBytes = 7L,
            mtimeEpochMs = 0L,
            lastSeenAtEpochMs = 0L,
        )
        val catalog = catalog(fileDao = fileDao)

        val handle = catalog.fetchFile("item-1", BookFormat.Epub)
        val local = handle as CatalogFileHandle.Local
        assertEquals(tmp.absolutePath, local.path)
        assertEquals(BookFormat.Epub, local.format)
        assertEquals(7L, local.sizeBytes)

        tmp.delete()
    }

    @Test
    fun `fetchFile throws when the format does not match the stored kind`() = runTest {
        val fileDao = InMemoryFileDao()
        fileDao.rows[sourceId to "item-1"] = LocalFilesFileEntity(
            sourceId = sourceId,
            sourceItemId = "item-1",
            folderTreeUri = "content://tree/A",
            originalUri = "content://tree/A/document/1",
            copiedPath = "/tmp/x.pdf",
            coverPath = null,
            format = "pdf",
            sizeBytes = 0L,
            mtimeEpochMs = 0L,
            lastSeenAtEpochMs = 0L,
        )
        val catalog = catalog(fileDao = fileDao)
        val ex = runCatching { catalog.fetchFile("item-1", BookFormat.Epub) }.exceptionOrNull()
        assertTrue(ex is CatalogException.UnsupportedFormat)
    }

    @Test
    fun `fetchFile throws when no local file row exists for the item`() = runTest {
        val catalog = catalog()
        val ex = runCatching { catalog.fetchFile("missing", BookFormat.Epub) }.exceptionOrNull()
        assertTrue(ex is CatalogException.UnsupportedFormat)
    }

    @Test
    fun `connectivityCheck reports healthy regardless of state`() = runTest {
        val health = catalog().connectivityCheck()
        assertTrue(health.isReachable)
        assertEquals("local", health.serverVersion)
    }

    @Test
    fun `listSeries aggregates library_items by seriesName and lists items alphabetically`() = runTest {
        val items = FakeLibraryItemDao()
        items.emit(
            sourceId,
            listOf(
                epubItem("a", "Book A", seriesName = "Cycle"),
                epubItem("b", "Book B", seriesName = "Cycle"),
                epubItem("c", "Loner", seriesName = null),
                epubItem("d", "Other 1", seriesName = "Other"),
            ),
        )
        val catalog = catalog(items = items)

        val series = catalog.listSeries(libraryId)
        assertEquals(listOf("Cycle", "Other"), series.map { it.name })
        val cycle = series.first { it.name == "Cycle" }
        assertEquals(2, cycle.bookCount)
        assertEquals(listOf("a", "b"), cycle.items.map { it.itemId })

        val inCycle = catalog.listItemsInSeries(libraryId, "Cycle")
        assertEquals(listOf("Book A", "Book B"), inCycle.map { it.title })
    }

    @Test
    fun `listSeries is empty when no items advertise a series`() = runTest {
        val items = FakeLibraryItemDao().also {
            it.emit(sourceId, listOf(epubItem("a", "Only Book", seriesName = null)))
        }
        val catalog = catalog(items = items)
        assertTrue(catalog.listSeries(libraryId).isEmpty())
    }

    @Test
    fun `RECENTLY_OPENED sort is not supported at the Catalog layer`() = runTest {
        val items = FakeLibraryItemDao().also { it.emit(sourceId, listOf(epubItem("a", "a"))) }
        val catalog = catalog(items = items)
        val ex = runCatching { catalog.browse(libraryId, SortKey.RECENTLY_OPENED) }.exceptionOrNull()
        assertTrue(ex is CatalogException.UnsupportedFormat)
    }

    // region helpers

    private fun catalog(
        folderDao: LocalFilesFolderDao = InMemoryFolderDao(),
        fileDao: LocalFilesFileDao = InMemoryFileDao(),
        items: FakeLibraryItemDao = FakeLibraryItemDao(),
    ) = LocalFilesCatalog(
        sourceId = sourceId,
        folderDao = folderDao,
        fileDao = fileDao,
        itemDao = items,
    )

    private fun epubItem(
        id: String,
        title: String,
        author: String = "",
        addedAt: Long? = null,
        seriesName: String? = null,
    ): LibraryItemEntity = LibraryItemEntity(
        sourceId = sourceId,
        id = id,
        libraryId = libraryId,
        title = title,
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        ebookFormat = "epub",
        addedAt = addedAt,
        seriesName = seriesName,
    )

    // endregion

    // region in-memory DAOs (mirrors of the scanner test's fakes so this suite is self-contained)

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
        ) { /* not exercised */ }
        override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileEntity> = emptyList()
        override suspend fun delete(sourceId: String, sourceItemId: String) {
            rows.remove(sourceId to sourceItemId)
        }
    }

    // endregion
}
