package com.riffle.core.data.gutenberg

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.domain.Clock
import com.riffle.core.domain.EbookFormat
import javax.inject.Inject

/**
 * Bridges a browsed [CatalogItem] into the local `library_items` cache when the user opens it.
 *
 * Gutenberg is an unbounded remote catalogue — [LibraryRepositoryImpl.refreshLibraryItems]
 * intentionally does NOT populate `library_items` for it (see [SourceType.isUnboundedCatalog]).
 * But the reader resolves the item via `LibraryObserver.getItem(itemId)`, so opening a
 * Gutenberg item requires a row to exist. This upserter is the on-demand hop: called from
 * [com.riffle.app.feature.source.gutenberg.GutenbergBrowseViewModel] just before navigation.
 *
 * Mirrors the shape of [com.riffle.core.data.chitanka.ChitankaLibraryItemUpserter].
 */
class GutenbergLibraryItemUpserter @Inject constructor(
    private val libraryItemDao: LibraryItemDao,
    private val clock: Clock,
) {
    suspend fun upsert(sourceId: String, item: CatalogItem) {
        val entity = item.toEntity(sourceId)
        libraryItemDao.insertOrIgnore(listOf(entity))
        libraryItemDao.updateMetadata(LibraryItemMetadata.from(entity))
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
        // Gutendex's JSON payload carries no `addedAt`, so stamp the moment this row enters
        // `library_items` — matches the ADR-0042 semantics of "when the user opened it".
        addedAt = addedAt ?: clock.nowMs(),
        isbn = isbn,
        asin = asin,
    )

    private fun BookFormat.toEbookFormat(): EbookFormat = when (this) {
        BookFormat.Epub -> EbookFormat.Epub
        BookFormat.Pdf -> EbookFormat.Pdf
        BookFormat.Cbz -> EbookFormat.Cbz
        BookFormat.Audiobook -> EbookFormat.Unsupported
        BookFormat.Unsupported -> EbookFormat.Unsupported
    }
}
