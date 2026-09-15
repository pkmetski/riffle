package com.riffle.shared.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.models.EbookFormat
import com.riffle.feature.library.WebSourceLibraryItemUpserter

/**
 * iOS implementation of [WebSourceLibraryItemUpserter]. Mirrors the Android
 * `core/data/src/androidMain` version — inserts a sentinel-timestamped row on browse-tap and
 * splices back any locally-tracked timestamps from the existing row so they are not overwritten.
 */
internal class IosWebSourceLibraryItemUpserterImpl(
    private val libraryItemDao: LibraryItemDao,
) : WebSourceLibraryItemUpserter {

    override suspend fun upsert(sourceId: String, item: CatalogItem) {
        val entity = item.toEntity(sourceId)
        libraryItemDao.insertOrIgnore(listOf(entity))
        val existing = libraryItemDao.getById(sourceId, entity.id)
        libraryItemDao.updateMetadata(
            LibraryItemMetadata.from(
                entity.copy(
                    addedAt = existing?.addedAt ?: 0L,
                    lastOpenedAt = existing?.lastOpenedAt,
                    finishedAt = existing?.finishedAt,
                ),
            ),
        )
    }

    private fun CatalogItem.toEntity(sourceId: String): LibraryItemEntity = LibraryItemEntity(
        sourceId = sourceId,
        id = id,
        libraryId = rootId,
        title = title,
        author = author,
        coverUrl = coverUrl ?: "",
        readingProgress = readingProgress ?: 0f,
        ebookFileIno = ebookFileIno,
        ebookFormat = ebookFormat.toEbookFormat().toStorageString(),
        hasAudio = hasAudio,
        audioDurationSec = audioDurationSec,
        description = description,
        seriesName = seriesName,
        seriesSequence = seriesSequence,
        publishedYear = publishedYear,
        genres = genres.joinToString(","),
        publisher = publisher,
        language = language,
        addedAt = 0L,
        isbn = isbn,
        asin = asin,
        pageCount = pageCount,
    )

    private fun BookFormat.toEbookFormat(): EbookFormat = when (this) {
        BookFormat.Epub -> EbookFormat.Epub
        BookFormat.Pdf -> EbookFormat.Pdf
        BookFormat.Cbz -> EbookFormat.Cbz
        BookFormat.Audiobook -> EbookFormat.Unsupported
        BookFormat.Unsupported -> EbookFormat.Unsupported
    }
}
