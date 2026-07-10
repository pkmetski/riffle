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
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SeriesEntryOrdering
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.SourceType
import java.io.File
import java.io.FileInputStream

/**
 * The LocalFiles-backed [Catalog]. Reads from local Room storage populated by
 * [LocalFilesScanner]: `library_items` rows carry the browsable metadata, `local_files_files`
 * rows carry the copied-in file path used to serve bytes. There is no network — every method
 * is trivially offline-safe, which is why LocalFiles implements [OfflineBrowseCapability]. The
 * catalog exposes a single synthetic root (`local:root`) matching the `libraryId` the scanner
 * writes for every ingested file. [SeriesCapability] is derived from EPUB `belongs-to-collection`
 * metadata already extracted into `library_items.seriesName` — position within the series comes
 * from `library_items.seriesSequence` (EPUB3 `group-position` / Calibre `series_index`), and
 * ordering flows through [SeriesEntryOrdering] so numbered entries sort by value, non-numeric
 * sequences fall in after, and unnumbered books land last by title. The Series tab hides itself
 * in the UI when the aggregation yields zero series.
 */
class LocalFilesCatalog(
    private val sourceId: String,
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val itemDao: LibraryItemDao,
) : Catalog, SeriesCapability, OfflineBrowseCapability {

    override val sourceType: SourceType = SourceType.LOCAL_FILES

    override suspend fun listRoots(): List<CatalogRoot> = listOf(
        CatalogRoot(id = LOCAL_ROOT_ID, name = "Local Files", mediaType = "book"),
    )

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        val items = itemDao.listByLibraryId(sourceId, rootId)
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
        val hits = itemDao.listByLibraryId(sourceId, rootId)
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
        // Row exists but the copied-in blob is gone (external cleanup, storage corruption). Surface
        // this the same way the "no row" branch does so reader code catching CatalogException gets
        // a uniform error rather than a raw FileNotFoundException.
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

    /**
     * LocalFiles has no server to check — it's always reachable. Callers that need to gate on
     * "can this Source actually serve a book" use [OfflineBrowseCapability] + folder-level health
     * (see [LocalFilesFolderHealth]), which surfaces SAF-URI revocation separately.
     */
    override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(
        isReachable = true,
        serverVersion = "local",
        latencyMs = 0L,
    )

    // region SeriesCapability

    override suspend fun listSeries(rootId: String): List<CatalogSeries> {
        val rows = itemDao.listByLibraryId(sourceId, rootId)
            .filter { !it.seriesName.isNullOrBlank() }
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
        itemDao.listByLibraryId(sourceId, rootId)
            .filter { it.seriesName == seriesId }
            .sortedWith(entityOrdering)
            .map { it.toCatalogItem() }

    // endregion

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

    // Delegated to SeriesEntryOrdering so the sequence semantics (numeric-first, non-numeric next,
    // missing last, title tiebreaker) match every other Catalog. Never sort a series by title alone.
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
        const val LOCAL_ROOT_ID: String = "local:root"
    }
}
