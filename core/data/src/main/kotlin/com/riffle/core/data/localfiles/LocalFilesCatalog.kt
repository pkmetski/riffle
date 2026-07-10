package com.riffle.core.data.localfiles

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogSeries
import com.riffle.core.catalog.CatalogSeriesEntry
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SeriesEntryOrdering
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.SourceType
import java.io.File
import java.io.FileInputStream

/**
 * The LocalFiles-backed [Catalog]. Each configured folder is its own root, named after the
 * folder's `displayName`. Items in a root are the files whose `local_files_file_folders`
 * membership row points at that folder's `treeUri` — a book present in two folders appears
 * under both roots, backed by a single `library_items` row (identity-hashed).
 *
 * The catalog never uses `library_items.libraryId` directly for browse — the source of truth for
 * folder membership is the junction table. `library_items.libraryId` is written for compatibility
 * with the rest of the codebase (it names *some* folder that currently contains the book) but is
 * a hint, not the query key.
 */
class LocalFilesCatalog(
    private val sourceId: String,
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val itemDao: LibraryItemDao,
) : Catalog, SeriesCapability, OfflineBrowseCapability {

    override val sourceType: SourceType = SourceType.LOCAL_FILES

    override suspend fun listRoots(): List<CatalogRoot> =
        folderDao.forSource(sourceId).map { folder ->
            CatalogRoot(id = folder.libraryId, name = folder.displayName, mediaType = "book")
        }

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        // LocalFiles has no server-side facets — `facet` is ignored.
        val items = itemsInFolderLibrary(rootId)
            .map { it.toCatalogItem() }
            .sortedWith(comparatorFor(sort))
        return items.pageOf(page, pageSize)
    }

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val hits = itemsInFolderLibrary(rootId)
            .filter {
                it.title.lowercase().contains(needle) || it.author.lowercase().contains(needle)
            }
            .map { it.toCatalogItem() }
            .sortedBy { it.title.lowercase() }
        return hits.pageOf(page, pageSize)
    }

    override suspend fun getItem(itemId: String): CatalogItem? =
        itemDao.getById(sourceId, itemId)?.toCatalogItem()

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        val file = requireFile(itemId, format)
        return CatalogFileHandle.Local(
            path = file.copiedPath,
            format = format,
            sizeBytes = file.sizeBytes,
        )
    }

    override suspend fun openFile(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
    ): CatalogFileStream {
        val file = requireFile(itemId, format)
        val f = File(file.copiedPath)
        if (!f.exists()) {
            throw CatalogException.UnsupportedFormat(
                "LocalFiles copied path missing for itemId=$itemId path=${file.copiedPath}",
            )
        }
        val length = f.length()
        val stream = FileInputStream(f)
        return object : CatalogFileStream {
            override val contentLength: Long = length
            override fun byteStream(): java.io.InputStream = stream
            override fun close() { stream.close() }
        }
    }

    override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(
        isReachable = true,
        serverVersion = "local",
        latencyMs = 0L,
    )

    // region SeriesCapability

    override suspend fun listSeries(rootId: String): List<CatalogSeries> {
        val rows = itemsInFolderLibrary(rootId).filter { !it.seriesName.isNullOrBlank() }
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.seriesName!! }
            .toSortedMap()
            .map { (name, items) ->
                val sorted = items.sortedWith(entityOrdering)
                CatalogSeries(
                    id = name,
                    rootId = rootId,
                    name = name,
                    coverUrl = sorted.firstNotNullOfOrNull { it.coverUrl },
                    bookCount = sorted.size,
                    items = sorted.map { CatalogSeriesEntry(itemId = it.id, sequence = it.seriesSequence) },
                )
            }
    }

    override suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem> =
        itemsInFolderLibrary(rootId)
            .filter { it.seriesName == seriesId }
            .sortedWith(entityOrdering)
            .map { it.toCatalogItem() }

    // endregion

    /**
     * Every library_item row whose file has a membership in the folder named by [libraryId].
     * Callers that expect all items in a library at once — browse, search, listSeries — pull the
     * full list and page/filter in memory, matching the pre-junction implementation. Never falls
     * back to `library_items.libraryId`: that column is a compatibility hint, not a query key.
     */
    private suspend fun itemsInFolderLibrary(libraryId: String): List<LibraryItemEntity> {
        val folder = folderDao.getByLibraryId(sourceId, libraryId) ?: return emptyList()
        val ids = fileFolderDao.itemIdsInFolder(sourceId, folder.treeUri)
        if (ids.isEmpty()) return emptyList()
        return itemDao.listByIds(sourceId, ids)
    }

    private suspend fun requireFile(itemId: String, format: BookFormat): LocalFilesFileEntity {
        val row = fileDao.findById(sourceId, itemId)
            ?: throw CatalogException.UnsupportedFormat(
                "No local file for itemId=$itemId in sourceId=$sourceId",
            )
        val expected = format.toStorageString()
        if (expected != null && row.format != expected) {
            throw CatalogException.UnsupportedFormat(
                "Requested format=$format but stored format=${row.format} for itemId=$itemId",
            )
        }
        return row
    }

    private fun BookFormat.toStorageString(): String? = when (this) {
        BookFormat.Epub -> EbookFormat.STORAGE_EPUB
        BookFormat.Pdf -> EbookFormat.STORAGE_PDF
        BookFormat.Cbz -> EbookFormat.STORAGE_CBZ
        BookFormat.Audiobook, BookFormat.Unsupported -> null
    }

    private fun LibraryItemEntity.toCatalogItem(): CatalogItem = CatalogItem(
        id = id,
        rootId = libraryId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        ebookFormat = when (ebookFormat) {
            EbookFormat.STORAGE_EPUB -> BookFormat.Epub
            EbookFormat.STORAGE_PDF -> BookFormat.Pdf
            EbookFormat.STORAGE_CBZ -> BookFormat.Cbz
            else -> BookFormat.Unsupported
        },
        hasAudio = hasAudio,
        audioDurationSec = audioDurationSec,
        ebookFileIno = ebookFileIno,
        description = description,
        seriesName = seriesName,
        publishedYear = publishedYear,
        genres = if (genres.isBlank()) emptyList() else genres.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        publisher = publisher,
        language = language,
        addedAt = addedAt,
        isbn = isbn,
        asin = asin,
        readingProgress = readingProgress,
        updatedAt = null,
    )

    private val entityOrdering: Comparator<LibraryItemEntity> =
        SeriesEntryOrdering.comparator(sequenceOf = { it.seriesSequence }, titleOf = { it.title })

    private fun comparatorFor(sort: SortKey): Comparator<CatalogItem> = when (sort) {
        SortKey.TITLE -> compareBy { it.title.lowercase() }
        SortKey.AUTHOR -> compareBy { it.author.lowercase() }
        SortKey.ADDED_AT -> compareByDescending { it.addedAt ?: 0L }
        SortKey.PUBLISHED_YEAR -> compareBy { it.publishedYear ?: "" }
        SortKey.RECENTLY_OPENED -> throw CatalogException.UnsupportedFormat(
            "SortKey.RECENTLY_OPENED is a local ordering — apply it above the Catalog layer",
        )
    }

    private fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
        val from = (page * pageSize).coerceAtLeast(0)
        if (from >= size) return emptyList()
        val to = (from + pageSize).coerceAtMost(size)
        return subList(from, to)
    }
}
