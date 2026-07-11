package com.riffle.core.data.chitanka

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.domain.EbookFormat
import javax.inject.Inject

/**
 * Bridges a browsed [CatalogItem] into the local `library_items` cache when the user taps it.
 *
 * Chitanka is an unbounded remote catalogue (ADR 0042) — [LibraryRepositoryImpl.refreshLibraryItems]
 * intentionally does NOT populate `library_items` for it. But the reader, the audiobook player, and
 * the detail screen resolve the item via `LibraryObserver.getItem(itemId)`, so opening a Chitanka
 * item requires a row to exist. This upserter is the on-demand hop: called from
 * [com.riffle.app.feature.source.chitanka.ChitankaBrowseViewModel] just before navigation.
 *
 * The row is stamped with `addedAt = 0` (sentinel) — a browse tap is *not* an "add to library"
 * intent, and [com.riffle.core.database.LibraryItemDao.observeRecentlyAdded] filters sentinel rows
 * out. Opening the reader promotes the row via
 * [com.riffle.core.database.LibraryItemDao.updateLastOpenedAt], which is where the item genuinely
 * enters the user's library.
 *
 * Uses the same insert-or-ignore + updateMetadata pattern as
 * [com.riffle.core.database.LibraryItemDao.replaceAllForLibrary] so a re-open preserves the local
 * `readingProgress` value.
 */
class ChitankaLibraryItemUpserter @Inject constructor(
    private val libraryItemDao: LibraryItemDao,
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
        // Sentinel: browse-tap is not intent to add. observeRecentlyAdded filters this out;
        // updateLastOpenedAt promotes it to the real timestamp on the first reader open.
        addedAt = 0L,
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
