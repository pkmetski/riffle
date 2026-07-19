package com.riffle.core.data.localfiles

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.StatsCapability
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.models.SourceType
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
    private val folderTreeUri = "content://tree/A"
    private val libraryId = "lib-A"

    @Test
    fun `capability shape - LocalFiles omits Downloads and Readaloud`() {
        val c: com.riffle.core.catalog.Catalog = catalog(folderDao = InMemoryFolderDao())
        // `is` checks not inline has<T>(): core:catalog compiles at JVM target 21 and this
        // module pins 17, so the reified inline can't cross the boundary. Same rationale as
        // [com.riffle.app.feature.library.LibraryItemsViewModel.tabVisibility].
        // Declared:
        assertTrue(c is SeriesCapability)
        assertTrue(c is OfflineBrowseCapability)
        assertTrue(c is ToReadListCapability)
        assertTrue(c is ReadCapability)
        // Not declared — LocalFiles has no fetch step, no readaloud bundles, no audiobook streaming.
        assertTrue(c !is DownloadsCapability)
        assertTrue(c !is ReadaloudCapability)
        assertTrue(c !is AudiobookMediaCapability)
        assertTrue(c !is CollectionsCapability)
        assertTrue(c !is PlaylistsCapability)
        assertTrue(c !is ProgressPeerCapability)
        assertTrue(c !is ReadingSessionsCapability)
        assertTrue(c !is StatsCapability)
        assertTrue(c !is BookmarksCapability)
    }

    @Test
    fun `listRoots returns one root per configured folder, named after the folder`() = runTest {
        val folderDao = InMemoryFolderDao()
        listOf(
            folderRow(treeUri = "content://tree/A", displayName = "Reading Now", libraryId = "lib-A"),
            folderRow(treeUri = "content://tree/B", displayName = "Archive", libraryId = "lib-B"),
        ).forEach { folderDao.store[it.sourceId to it.treeUri] = it }
        val roots = catalog(folderDao = folderDao).listRoots()
        assertEquals(setOf("lib-A" to "Reading Now", "lib-B" to "Archive"), roots.map { it.id to it.name }.toSet())
    }

    @Test
    fun `browse returns items in the folder-library, sorted by title, paged`() = runTest {
        val (fileFolderDao, folderDao, items) = twoBookFolder(
            titles = listOf("Cormac", "Alpha", "Bravo"),
        )
        val catalog = catalog(folderDao = folderDao, fileFolderDao = fileFolderDao, items = items)

        val page0 = catalog.browse(libraryId, SortKey.TITLE, page = 0, pageSize = 2)
        assertEquals(listOf("Alpha", "Bravo"), page0.map { it.title })

        val page1 = catalog.browse(libraryId, SortKey.TITLE, page = 1, pageSize = 2)
        assertEquals(listOf("Cormac"), page1.map { it.title })
    }

    @Test
    fun `browse sort by ADDED_AT is descending`() = runTest {
        val items = FakeLibraryItemDao()
        val bookItems = listOf(
            epubItem("old", "Old", addedAt = 100L),
            epubItem("new", "New", addedAt = 500L),
            epubItem("mid", "Mid", addedAt = 250L),
        )
        items.emit(sourceId, bookItems)
        val folderDao = folderWith(libraryId)
        val fileFolderDao = fileFolderWith(bookItems.map { it.id })
        val catalog = catalog(folderDao = folderDao, fileFolderDao = fileFolderDao, items = items)
        val browsed = catalog.browse(libraryId, SortKey.ADDED_AT)
        assertEquals(listOf("New", "Mid", "Old"), browsed.map { it.title })
    }

    @Test
    fun `search matches title and author case-insensitively`() = runTest {
        val items = FakeLibraryItemDao()
        val bookItems = listOf(
            epubItem("1", "The Hobbit", author = "J.R.R. Tolkien"),
            epubItem("2", "Dune", author = "Frank Herbert"),
            epubItem("3", "Foundation", author = "Isaac Asimov"),
        )
        items.emit(sourceId, bookItems)
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(bookItems.map { it.id }),
            items = items,
        )

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
    fun `listSeries aggregates library_items by seriesName and falls back to title when sequence is missing`() = runTest {
        val items = FakeLibraryItemDao()
        val bookItems = listOf(
            epubItem("a", "Book A", seriesName = "Cycle"),
            epubItem("b", "Book B", seriesName = "Cycle"),
            epubItem("c", "Loner", seriesName = null),
            epubItem("d", "Other 1", seriesName = "Other"),
        )
        items.emit(sourceId, bookItems)
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(bookItems.map { it.id }),
            items = items,
        )

        val series = catalog.listSeries(libraryId)
        assertEquals(listOf("Cycle", "Other"), series.map { it.name })
        val cycle = series.first { it.name == "Cycle" }
        assertEquals(2, cycle.bookCount)
        assertEquals(listOf("a", "b"), cycle.items.map { it.itemId })

        val inCycle = catalog.listItemsInSeries(libraryId, "Cycle")
        assertEquals(listOf("Book A", "Book B"), inCycle.map { it.title })
    }

    @Test
    fun `listSeries orders entries by numeric seriesSequence, not by title`() = runTest {
        val items = FakeLibraryItemDao()
        val bookItems = listOf(
            epubItem("ten", "Book Ten", seriesName = "Cycle", seriesSequence = "10"),
            epubItem("two", "Book Two", seriesName = "Cycle", seriesSequence = "2"),
            epubItem("one", "Book One", seriesName = "Cycle", seriesSequence = "1"),
        )
        items.emit(sourceId, bookItems)
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(bookItems.map { it.id }),
            items = items,
        )

        val cycle = catalog.listSeries(libraryId).first()
        assertEquals(listOf("one", "two", "ten"), cycle.items.map { it.itemId })
        assertEquals(listOf("1", "2", "10"), cycle.items.map { it.sequence })

        assertEquals(
            listOf("Book One", "Book Two", "Book Ten"),
            catalog.listItemsInSeries(libraryId, "Cycle").map { it.title },
        )
    }

    @Test
    fun `listSeries is empty when no items advertise a series`() = runTest {
        val items = FakeLibraryItemDao().also {
            it.emit(sourceId, listOf(epubItem("a", "Only Book", seriesName = null)))
        }
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(listOf("a")),
            items = items,
        )
        assertTrue(catalog.listSeries(libraryId).isEmpty())
    }

    @Test
    fun `capability set excludes every non-LocalFiles surface`() {
        val catalog: com.riffle.core.catalog.Catalog = catalog()

        assertTrue(catalog is com.riffle.core.catalog.SeriesCapability)
        assertTrue(catalog is com.riffle.core.catalog.OfflineBrowseCapability)

        assertEquals(false, catalog is com.riffle.core.catalog.CollectionsCapability)
        assertEquals(false, catalog is com.riffle.core.catalog.PlaylistsCapability)
        assertEquals(false, catalog is com.riffle.core.catalog.AudiobookMediaCapability)
        assertEquals(false, catalog is com.riffle.core.catalog.ReadingSessionsCapability)
        assertEquals(false, catalog is com.riffle.core.catalog.StatsCapability)
    }

    @Test
    fun `RECENTLY_OPENED sort is not supported at the Catalog layer`() = runTest {
        val items = FakeLibraryItemDao().also { it.emit(sourceId, listOf(epubItem("a", "a"))) }
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(listOf("a")),
            items = items,
        )
        val ex = runCatching { catalog.browse(libraryId, SortKey.RECENTLY_OPENED) }.exceptionOrNull()
        assertTrue(ex is CatalogException.UnsupportedFormat)
    }

    @Test
    fun `books shared across two folders appear in both folders' libraries`() = runTest {
        val items = FakeLibraryItemDao()
        val shared = epubItem("shared", "Shared Book")
        val onlyInA = epubItem("only-a", "A-only")
        val onlyInB = epubItem("only-b", "B-only")
        items.emit(sourceId, listOf(shared, onlyInA, onlyInB))
        val folderDao = InMemoryFolderDao()
        listOf(
            folderRow(treeUri = "tree-a", displayName = "A", libraryId = "lib-A"),
            folderRow(treeUri = "tree-b", displayName = "B", libraryId = "lib-B"),
        ).forEach { folderDao.store[it.sourceId to it.treeUri] = it }
        val fileFolderDao = InMemoryFileFolderDao()
        listOf(
            membership("shared", "tree-a"),
            membership("shared", "tree-b"),
            membership("only-a", "tree-a"),
            membership("only-b", "tree-b"),
        ).forEach { fileFolderDao.rows[Triple(it.sourceId, it.sourceItemId, it.folderTreeUri)] = it }
        val catalog = catalog(folderDao = folderDao, fileFolderDao = fileFolderDao, items = items)
        val a = catalog.browse("lib-A", SortKey.TITLE).map { it.title }.toSet()
        val b = catalog.browse("lib-B", SortKey.TITLE).map { it.title }.toSet()
        assertEquals(setOf("A-only", "Shared Book"), a)
        assertEquals(setOf("B-only", "Shared Book"), b)
    }

    // region helpers

    private fun folderRow(treeUri: String, displayName: String, libraryId: String) =
        LocalFilesFolderEntity(
            sourceId = sourceId,
            treeUri = treeUri,
            displayName = displayName,
            addedAtEpochMs = 0L,
            libraryId = libraryId,
        )

    private fun membership(itemId: String, treeUri: String) = LocalFilesFileFolderEntity(
        sourceId = sourceId,
        sourceItemId = itemId,
        folderTreeUri = treeUri,
        lastSeenAtEpochMs = 0L,
    )

    private fun folderWith(libraryId: String): InMemoryFolderDao {
        val dao = InMemoryFolderDao()
        val row = folderRow(treeUri = folderTreeUri, displayName = "A", libraryId = libraryId)
        dao.store[row.sourceId to row.treeUri] = row
        return dao
    }

    private fun fileFolderWith(itemIds: List<String>): InMemoryFileFolderDao {
        val dao = InMemoryFileFolderDao()
        for (id in itemIds) {
            val m = membership(id, folderTreeUri)
            dao.rows[Triple(m.sourceId, m.sourceItemId, m.folderTreeUri)] = m
        }
        return dao
    }

    private fun twoBookFolder(titles: List<String>): Triple<InMemoryFileFolderDao, InMemoryFolderDao, FakeLibraryItemDao> {
        val items = FakeLibraryItemDao()
        val entries = titles.mapIndexed { i, t -> epubItem("id-$i", t) }
        items.emit(sourceId, entries)
        return Triple(fileFolderWith(entries.map { it.id }), folderWith(libraryId), items)
    }

    private fun catalog(
        folderDao: LocalFilesFolderDao = InMemoryFolderDao(),
        fileDao: LocalFilesFileDao = InMemoryFileDao(),
        fileFolderDao: LocalFilesFileFolderDao = InMemoryFileFolderDao(),
        items: FakeLibraryItemDao = FakeLibraryItemDao(),
    ) = LocalFilesCatalog(
        sourceId = sourceId,
        folderDao = folderDao,
        fileDao = fileDao,
        fileFolderDao = fileFolderDao,
        itemDao = items,
    )

    private fun epubItem(
        id: String,
        title: String,
        author: String = "",
        addedAt: Long? = null,
        seriesName: String? = null,
        seriesSequence: String? = null,
    ): LibraryItemEntity = LibraryItemEntity(
        sourceId = sourceId,
        id = id,
        libraryId = libraryId,
        title = title,
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        ebookFormat = "epub",
        addedAt = addedAt ?: 0L,
        seriesName = seriesName,
        seriesSequence = seriesSequence,
    )

    // endregion

    // region in-memory DAOs

    private class InMemoryFolderDao : LocalFilesFolderDao {
        val store = mutableMapOf<Pair<String, String>, LocalFilesFolderEntity>()
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

    private class InMemoryFileDao : LocalFilesFileDao {
        val rows = mutableMapOf<Pair<String, String>, LocalFilesFileEntity>()
        override suspend fun upsert(entity: LocalFilesFileEntity) {
            rows[entity.sourceId to entity.sourceItemId] = entity
        }
        override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? =
            rows[sourceId to sourceItemId]
        override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> =
            rows.values.filter { it.sourceId == sourceId }
        override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {}
        override suspend fun delete(sourceId: String, sourceItemId: String) {
            rows.remove(sourceId to sourceItemId)
        }
    }

    private class InMemoryFileFolderDao : LocalFilesFileFolderDao {
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
        override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileFolderEntity> = emptyList()
        override suspend fun delete(sourceId: String, sourceItemId: String, folderTreeUri: String) {
            rows.remove(Triple(sourceId, sourceItemId, folderTreeUri))
        }
        override suspend fun deleteFolder(sourceId: String, folderTreeUri: String) {
            rows.entries.removeIf { it.value.sourceId == sourceId && it.value.folderTreeUri == folderTreeUri }
        }
        override suspend fun deleteFile(sourceId: String, sourceItemId: String) {
            rows.entries.removeIf { it.value.sourceId == sourceId && it.value.sourceItemId == sourceItemId }
        }
        override suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity> = emptyList()
    }

    // endregion
}
