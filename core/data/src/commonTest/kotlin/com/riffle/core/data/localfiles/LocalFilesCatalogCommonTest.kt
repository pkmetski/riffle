package com.riffle.core.data.localfiles

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.OriginalCoverCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.StatsCapability
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.data.FakeLibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFilesCatalogCommonTest {

    private val sourceId = "src-1"
    private val folderTreeUri = "content://tree/A"
    private val libraryId = "lib-A"

    @Test
    fun capabilityShapeLocalFilesOmitsDownloadsAndReadaloud() {
        val c: com.riffle.core.catalog.Catalog = catalog(folderDao = InMemoryFolderDao())
        assertTrue(c is SeriesCapability)
        assertTrue(c is OfflineBrowseCapability)
        assertTrue(c is ToReadListCapability)
        assertTrue(c is ReadCapability)
        assertTrue(c is OriginalCoverCapability)
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
    fun listRootsReturnsOneRootPerConfiguredFolder() = runTest {
        val folderDao = InMemoryFolderDao()
        listOf(
            folderRow(treeUri = "content://tree/A", displayName = "Reading Now", libraryId = "lib-A"),
            folderRow(treeUri = "content://tree/B", displayName = "Archive", libraryId = "lib-B"),
        ).forEach { folderDao.store[it.sourceId to it.treeUri] = it }
        val roots = catalog(folderDao = folderDao).listRoots()
        assertEquals(setOf("lib-A" to "Reading Now", "lib-B" to "Archive"), roots.map { it.id to it.name }.toSet())
    }

    @Test
    fun browseReturnsSortedPagedResults() = runTest {
        val (fileFolderDao, folderDao, items) = twoBookFolder(titles = listOf("Cormac", "Alpha", "Bravo"))
        val catalog = catalog(folderDao = folderDao, fileFolderDao = fileFolderDao, items = items)

        val page0 = catalog.browse(libraryId, SortKey.TITLE, page = 0, pageSize = 2)
        assertEquals(listOf("Alpha", "Bravo"), page0.map { it.title })

        val page1 = catalog.browse(libraryId, SortKey.TITLE, page = 1, pageSize = 2)
        assertEquals(listOf("Cormac"), page1.map { it.title })
    }

    @Test
    fun browseSortByAddedAtIsDescending() = runTest {
        val items = FakeLibraryItemDao()
        val bookItems = listOf(
            epubItem("old", "Old", addedAt = 100L),
            epubItem("new", "New", addedAt = 500L),
            epubItem("mid", "Mid", addedAt = 250L),
        )
        items.emit(sourceId, bookItems)
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(bookItems.map { it.id }),
            items = items,
        )
        val browsed = catalog.browse(libraryId, SortKey.ADDED_AT)
        assertEquals(listOf("New", "Mid", "Old"), browsed.map { it.title })
    }

    @Test
    fun searchMatchesTitleAndAuthorCaseInsensitively() = runTest {
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
    fun getItemReturnsNullForMissingItem() = runTest {
        val items = FakeLibraryItemDao().also { it.emit(sourceId, listOf(epubItem("x", "x"))) }
        val catalog = catalog(items = items)
        assertEquals("x", catalog.getItem("x")?.title)
        assertNull(catalog.getItem("missing"))
    }

    @Test
    fun fetchFileReturnsLocalHandleFromCopiedPath() = runTest {
        val fileDao = InMemoryFileDao()
        fileDao.rows[sourceId to "item-1"] = LocalFilesFileEntity(
            sourceId = sourceId,
            sourceItemId = "item-1",
            originalUri = "content://tree/A/document/1",
            copiedPath = "/tmp/book.epub",
            coverPath = null,
            format = "epub",
            sizeBytes = 7L,
            mtimeEpochMs = 0L,
            lastSeenAtEpochMs = 0L,
        )
        val catalog = catalog(fileDao = fileDao)

        val handle = catalog.fetchFile("item-1", BookFormat.Epub)
        val local = handle as CatalogFileHandle.Local
        assertEquals("/tmp/book.epub", local.path)
        assertEquals(BookFormat.Epub, local.format)
        assertEquals(7L, local.sizeBytes)
    }

    @Test
    fun fetchFileThrowsOnFormatMismatch() = runTest {
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
    fun fetchFileThrowsForMissingRow() = runTest {
        val catalog = catalog()
        val ex = runCatching { catalog.fetchFile("missing", BookFormat.Epub) }.exceptionOrNull()
        assertTrue(ex is CatalogException.UnsupportedFormat)
    }

    @Test
    fun connectivityCheckReportsHealthy() = runTest {
        val health = catalog().connectivityCheck()
        assertTrue(health.isReachable)
        assertEquals("local", health.serverVersion)
    }

    @Test
    fun listSeriesAggregatesBySeriesName() = runTest {
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
    }

    @Test
    fun listSeriesOrdersByNumericSequence() = runTest {
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
    }

    @Test
    fun listSeriesIsEmptyWhenNoSeriesPresent() = runTest {
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
    fun browseAppliesTitleOverride() = runTest {
        val items = FakeLibraryItemDao()
        val entry = epubItem("book-1", "Bad Extracted Title", author = "Old Author")
        items.emit(sourceId, listOf(entry))
        val overrideDao = InMemoryOverrideDao()
        overrideDao.upsert(
            LocalFileMetadataOverrideEntity(
                sourceId = sourceId,
                sourceItemId = "book-1",
                title = "Correct Title",
                author = "New Author",
                seriesName = null,
                seriesIndex = null,
            )
        )
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(listOf("book-1")),
            items = items,
            overrideDao = overrideDao,
        )
        val results = catalog.browse(libraryId, SortKey.TITLE)
        assertEquals(1, results.size)
        assertEquals("Correct Title", results[0].title)
        assertEquals("New Author", results[0].author)
    }

    @Test
    fun browseUsesExtractedValuesWhenNoOverride() = runTest {
        val items = FakeLibraryItemDao()
        val entry = epubItem("book-2", "Extracted Title", author = "Extracted Author")
        items.emit(sourceId, listOf(entry))
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(listOf("book-2")),
            items = items,
        )
        val results = catalog.browse(libraryId, SortKey.TITLE)
        assertEquals("Extracted Title", results[0].title)
        assertEquals("Extracted Author", results[0].author)
    }

    @Test
    fun getItemAppliesOverride() = runTest {
        val items = FakeLibraryItemDao()
        val entry = epubItem("book-3", "Raw Title")
        items.emit(sourceId, listOf(entry))
        val overrideDao = InMemoryOverrideDao()
        overrideDao.upsert(
            LocalFileMetadataOverrideEntity(
                sourceId = sourceId,
                sourceItemId = "book-3",
                title = "Overridden Title",
                author = null,
                seriesName = "My Series",
                seriesIndex = 2.0,
            )
        )
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileFolderDao = fileFolderWith(listOf("book-3")),
            items = items,
            overrideDao = overrideDao,
        )
        val item = catalog.getItem("book-3")
        assertEquals("Overridden Title", item?.title)
        assertEquals("My Series", item?.seriesName)
        assertEquals("2", item?.seriesSequence)
    }

    @Test
    fun clearedCoverOverrideRestoresScannerCover() = runTest {
        val items = FakeLibraryItemDao()
        val scannerCoverPath = "/scanner-covers/book-5.jpg"
        items.emit(
            sourceId,
            listOf(epubItem("book-5", "Book").copy(coverUrl = "file:///local_covers/book-5.jpg")),
        )
        val fileDao = InMemoryFileDao().also { dao ->
            dao.rows[sourceId to "book-5"] = LocalFilesFileEntity(
                sourceId = sourceId,
                sourceItemId = "book-5",
                originalUri = "content://books/book-5.epub",
                copiedPath = "/copied/book-5.epub",
                coverPath = scannerCoverPath,
                format = "epub",
                sizeBytes = 100L,
                mtimeEpochMs = 0L,
                lastSeenAtEpochMs = 0L,
            )
        }
        val catalog = catalog(
            folderDao = folderWith(libraryId),
            fileDao = fileDao,
            fileFolderDao = fileFolderWith(listOf("book-5")),
            items = items,
        )

        val result = catalog.browse(libraryId, SortKey.TITLE).single()
        assertEquals("file://$scannerCoverPath", result.coverUrl)
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
        overrideDao: LocalFileMetadataOverrideDao = InMemoryOverrideDao(),
    ) = LocalFilesCatalog(
        sourceId = sourceId,
        folderDao = folderDao,
        fileDao = fileDao,
        fileFolderDao = fileFolderDao,
        itemDao = items,
        overrideDao = overrideDao,
        fileReader = FakeLocalFilesFileReader(),
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
        override suspend fun getForItems(
            sourceId: String,
            sourceItemIds: List<String>,
        ): List<LocalFilesFileEntity> =
            rows.values.filter { it.sourceId == sourceId && it.sourceItemId in sourceItemIds }
        override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {}
        override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) {
            rows[sourceId to sourceItemId]?.let { rows[sourceId to sourceItemId] = it.copy(displayName = displayName) }
        }
        override suspend fun delete(sourceId: String, sourceItemId: String) {
            rows.remove(sourceId to sourceItemId)
        }
    }

    private class InMemoryOverrideDao : LocalFileMetadataOverrideDao {
        val rows = mutableMapOf<Pair<String, String>, LocalFileMetadataOverrideEntity>()
        override suspend fun upsert(entity: LocalFileMetadataOverrideEntity) {
            rows[entity.sourceId to entity.sourceItemId] = entity
        }
        override fun observe(sourceId: String, sourceItemId: String): Flow<LocalFileMetadataOverrideEntity?> =
            MutableStateFlow(rows[sourceId to sourceItemId])
        override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFileMetadataOverrideEntity> =
            rows.values.filter { it.sourceId == sourceId && it.sourceItemId in sourceItemIds }
        override suspend fun getForItem(sourceId: String, sourceItemId: String): LocalFileMetadataOverrideEntity? =
            rows[sourceId to sourceItemId]
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
            val keys = rows.entries.filter { it.value.sourceId == sourceId && it.value.folderTreeUri == folderTreeUri }.map { it.key }
            keys.forEach { rows.remove(it) }
        }
        override suspend fun deleteFile(sourceId: String, sourceItemId: String) {
            val keys = rows.entries.filter { it.value.sourceId == sourceId && it.value.sourceItemId == sourceItemId }.map { it.key }
            keys.forEach { rows.remove(it) }
        }
        override suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity> = emptyList()
    }

    private class FakeLocalFilesFileReader : LocalFilesFileReader {
        override fun toFileUrl(path: String): String = "file://$path"
        override suspend fun <T> withFileStream(
            path: String,
            sizeBytes: Long?,
            block: suspend (CatalogFileStream) -> T,
        ): T = throw UnsupportedOperationException("FakeLocalFilesFileReader.withFileStream not implemented")
    }

    // endregion
}
