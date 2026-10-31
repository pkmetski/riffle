package com.riffle.core.data.websource

import com.riffle.core.catalog.CatalogItem
import com.riffle.core.data.ToReadItemUpserter
import com.riffle.core.data.toEbookFormat
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata

/**
 * Bridges a browsed [CatalogItem] into the local `library_items` cache when the user taps it.
 *
 * Web sources (Chitanka, Gutenberg, and future entries) are unbounded remote catalogues (ADR
 * 0051) — [com.riffle.core.data.LibraryRepositoryImpl.refreshLibraryItems] intentionally does
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
class WebSourceLibraryItemUpserter constructor(
    private val libraryItemDao: LibraryItemDao,
) : ToReadItemUpserter {
    override suspend fun upsert(sourceId: String, item: CatalogItem) {
        val entity = item.toEntity(sourceId)
        libraryItemDao.insertOrIgnore(listOf(entity))
        // Preserve every locally-tracked timestamp on the existing row. A CatalogItem carries
        // none of these — its toEntity() defaults them all to 0/null — so blindly copying the
        // entity into updateMetadata would silently null them out and erase real user state.
        // This matters most on the ADR-0052 24 h TTL cycle: a user opens a book (row picks up
        // lastOpenedAt + maybe finishedAt), then re-taps that same book from the browse listing
        // 24 h+ later — the gate refetches and calls this upserter, which used to overwrite:
        //   - addedAt      → sentinel 0, evicting the book from Recently Added
        //   - lastOpenedAt → null, evicting from Recently Opened
        //   - finishedAt   → null, undoing the "finished" stamp
        // Read the row once and splice the surviving locals back onto the fresh entity.
        val existing = libraryItemDao.getById(sourceId, entity.id)
        val m = LibraryItemMetadata.from(
            entity.copy(
                addedAt = existing?.addedAt ?: 0L,
                lastOpenedAt = existing?.lastOpenedAt,
                finishedAt = existing?.finishedAt,
            ),
        )
        libraryItemDao.updateMetadata(
            sourceId = m.sourceId, id = m.id, libraryId = m.libraryId,
            title = m.title, author = m.author, coverUrl = m.coverUrl,
            ebookFileIno = m.ebookFileIno, ebookFormat = m.ebookFormat,
            hasAudio = m.hasAudio, audioDurationSec = m.audioDurationSec,
            description = m.description, seriesName = m.seriesName,
            publishedYear = m.publishedYear, genres = m.genres,
            publisher = m.publisher, language = m.language,
            lastOpenedAt = m.lastOpenedAt, addedAt = m.addedAt,
            isbn = m.isbn, asin = m.asin, finishedAt = m.finishedAt,
            pageCount = m.pageCount,
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
        pageCount = pageCount,
    )
}
