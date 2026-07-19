package com.riffle.core.data.websource

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.models.EbookFormat
import javax.inject.Inject

/**
 * Bridges a browsed [CatalogItem] into the local `library_items` cache when the user taps it.
 *
 * Web sources (Chitanka, Gutenberg, and future entries) are unbounded remote catalogues (ADR
 * 0042) — [com.riffle.core.data.LibraryRepositoryImpl.refreshLibraryItems] intentionally does
 * NOT populate `library_items` for them. But the reader, the audiobook player, and the detail
 * screen resolve the item via `LibraryObserver.getItem(itemId)`, so opening a browsed item
 * requires a row to exist. This upserter is the on-demand hop: called from each web source's
 * BrowseViewModel just before navigation.
 *
 * The row is stamped with `addedAt = 0` (sentinel) — a browse tap is *not* an "add to library"
 * intent, and [LibraryItemDao.observeRecentlyAdded] filters sentinel rows out. Opening the
 * reader / audiobook player promotes the row via [LibraryItemDao.updateLastOpenedAt], which is
 * where the item genuinely enters the user's library.
 *
 * Uses the same insert-or-ignore + updateMetadata pattern as [LibraryItemDao.replaceAllForLibrary]
 * so a re-tap preserves the local `readingProgress` value.
 */
class WebSourceLibraryItemUpserter @Inject constructor(
    private val libraryItemDao: LibraryItemDao,
) {
    suspend fun upsert(sourceId: String, item: CatalogItem) {
        val entity = item.toEntity(sourceId)
        libraryItemDao.insertOrIgnore(listOf(entity))
        // Preserve every locally-tracked timestamp on the existing row. A CatalogItem carries
        // none of these — its toEntity() defaults them all to 0/null — so blindly copying the
        // entity into updateMetadata would silently null them out and erase real user state.
        // This matters most on the ADR-0043 24 h TTL cycle: a user opens a book (row picks up
        // lastOpenedAt + maybe finishedAt), then re-taps that same book from the browse listing
        // 24 h+ later — the gate refetches and calls this upserter, which used to overwrite:
        //   - addedAt      → sentinel 0, evicting the book from Recently Added
        //   - lastOpenedAt → null, evicting from Recently Opened
        //   - finishedAt   → null, undoing the "finished" stamp
        // Read the row once and splice the surviving locals back onto the fresh entity.
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
