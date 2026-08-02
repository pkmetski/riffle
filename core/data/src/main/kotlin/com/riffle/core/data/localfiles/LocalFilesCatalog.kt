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
import com.riffle.core.catalog.OriginalCoverCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SeriesEntryOrdering
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.SourceType
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
    private val overrideDao: LocalFileMetadataOverrideDao,
) : Catalog,
    SeriesCapability,
    OfflineBrowseCapability,
    ToReadListCapability,
    ReadCapability,
    OriginalCoverCapability {

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
        val items = itemsInFolderLibraryWithOverrides(rootId)
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
        val hits = itemsInFolderLibraryWithOverrides(rootId)
            .map { it.toCatalogItem() }
            .filter {
                it.title.lowercase().contains(needle) || it.author.lowercase().contains(needle)
            }
            .sortedBy { it.title.lowercase() }
        return hits.pageOf(page, pageSize)
    }

    override suspend fun getItem(itemId: String): CatalogItem? {
        val entity = itemDao.getById(sourceId, itemId) ?: return null
        val override = overrideDao.getForItem(sourceId, itemId)
        val file = fileDao.findById(sourceId, itemId)
        val originalCoverUrl = if (file == null) entity.coverUrl else file.coverPath?.toFileUrl()
        return entity.toCatalogItem(override, originalCoverUrl)
    }

    override suspend fun originalCoverUrl(itemId: String): String? =
        fileDao.findById(sourceId, itemId)?.coverPath?.toFileUrl()

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        val file = requireFile(itemId, format)
        return CatalogFileHandle.Local(
            path = file.copiedPath,
            format = format,
            sizeBytes = file.sizeBytes,
        )
    }

    override suspend fun <T> withFileStream(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        val file = requireFile(itemId, format)
        val f = File(file.copiedPath)
        if (!f.exists()) {
            throw CatalogException.UnsupportedFormat(
                "LocalFiles copied path missing for itemId=$itemId path=${file.copiedPath}",
            )
        }
        val length = f.length()
        return FileInputStream(f).use { stream ->
            block(
                object : CatalogFileStream {
                    override val contentLength: Long = length
                    override fun byteStream(): java.io.InputStream = stream
                    override fun close() { stream.close() }
                },
            )
        }
    }

    override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(
        isReachable = true,
        serverVersion = "local",
        latencyMs = 0L,
    )

    // region SeriesCapability

    override suspend fun listSeries(rootId: String): List<CatalogSeries> {
        val rows = itemsInFolderLibraryWithOverrides(rootId)
        val withSeries = rows.filter { row ->
            !(row.override?.seriesName ?: row.entity.seriesName).isNullOrBlank()
        }
        if (withSeries.isEmpty()) return emptyList()
        return withSeries.groupBy { row -> row.override?.seriesName ?: row.entity.seriesName!! }
            .toSortedMap()
            .map { (name, rowsForSeries) ->
                val sorted = rowsForSeries.sortedWith(
                    Comparator { a, b -> entityOrdering.compare(a.entity, b.entity) },
                )
                CatalogSeries(
                    id = name,
                    rootId = rootId,
                    name = name,
                    coverUrl = sorted.firstNotNullOfOrNull { it.toCatalogItem().coverUrl },
                    bookCount = sorted.size,
                    items = sorted.map { row ->
                        CatalogSeriesEntry(
                            itemId = row.entity.id,
                            sequence = row.entity.seriesSequence,
                        )
                    },
                )
            }
    }

    override suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem> =
        itemsInFolderLibraryWithOverrides(rootId)
            .filter { row -> (row.override?.seriesName ?: row.entity.seriesName) == seriesId }
            .sortedWith(Comparator { a, b -> entityOrdering.compare(a.entity, b.entity) })
            .map { it.toCatalogItem() }

    // endregion

    private suspend fun itemsInFolderLibrary(libraryId: String): List<LibraryItemEntity> {
        val folder = folderDao.getByLibraryId(sourceId, libraryId) ?: return emptyList()
        val ids = fileFolderDao.itemIdsInFolder(sourceId, folder.treeUri)
        if (ids.isEmpty()) return emptyList()
        // Chunk to stay under SQLite's SQLITE_MAX_VARIABLE_NUMBER (999 on pre-Android 12 devices).
        // A single folder library over 999 books would otherwise crash on browse/search.
        return ids.chunked(BIND_VAR_CHUNK).flatMap { itemDao.listByIds(sourceId, it) }
    }

    /**
     * Like [itemsInFolderLibrary] but also bulk-loads any metadata overrides for the result set
     * and the scanner-owned cover path. The latter is deliberately read from
     * `local_files_files`, not `library_items`: a library refresh writes override-applied catalog
     * metadata back to `library_items`, so that row cannot reliably identify the original cover.
     */
    private suspend fun itemsInFolderLibraryWithOverrides(
        libraryId: String,
    ): List<LocalItemRow> {
        val entities = itemsInFolderLibrary(libraryId)
        if (entities.isEmpty()) return emptyList()
        val overrides = entities.map { it.id }.chunked(BIND_VAR_CHUNK)
            .flatMap { overrideDao.getForItems(sourceId, it) }
            .associateBy { it.sourceItemId }
        val files = entities.map { it.id }.chunked(BIND_VAR_CHUNK)
            .flatMap { fileDao.getForItems(sourceId, it) }
            .associateBy { it.sourceItemId }
        return entities.map { entity ->
            val file = files[entity.id]
            LocalItemRow(
                entity = entity,
                override = overrides[entity.id],
                originalCoverUrl = if (file == null) {
                    entity.coverUrl
                } else {
                    file.coverPath?.toFileUrl()
                },
            )
        }
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

    private fun LibraryItemEntity.toCatalogItem(
        override: LocalFileMetadataOverrideEntity? = null,
        originalCoverUrl: String? = coverUrl,
    ): CatalogItem = CatalogItem(
        id = id,
        rootId = libraryId,
        title = override?.title ?: title,
        author = override?.author ?: author,
        coverUrl = override?.coverUrl ?: originalCoverUrl,
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
        seriesName = override?.seriesName ?: seriesName,
        seriesSequence = override?.seriesIndex?.let { idx ->
            if (idx == kotlin.math.floor(idx) && !idx.isInfinite()) idx.toLong().toString() else idx.toString()
        } ?: seriesSequence,
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

    private fun LocalItemRow.toCatalogItem(): CatalogItem =
        entity.toCatalogItem(override, originalCoverUrl)

    private fun String.toFileUrl(): String = File(this).toURI().toString()

    private data class LocalItemRow(
        val entity: LibraryItemEntity,
        val override: LocalFileMetadataOverrideEntity?,
        val originalCoverUrl: String?,
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

    companion object {
        // Below SQLITE_MAX_VARIABLE_NUMBER=999 on pre-Android-12 devices, with headroom for the
        // WHERE clause's non-bind-var operands.
        private const val BIND_VAR_CHUNK: Int = 900
    }
}
