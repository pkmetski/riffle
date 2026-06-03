package com.riffle.core.data

import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.EbookFormat
import com.riffle.core.network.NetworkStorytellerBook

/**
 * Builds local [LibraryItemEntity] rows from Storyteller readaloud books. Shared by the
 * active-server refresh ([LibraryRepositoryImpl.refreshStorytellerReadalouds]) and the proactive
 * StorytellerReadaloudSyncer so both produce identical rows (the matcher keys off
 * title/author/isbn/asin). Existing local reading progress and last-opened timestamps are merged
 * back in so a refresh never resets them.
 */
internal fun storytellerBooksToEntities(
    books: List<NetworkStorytellerBook>,
    libraryId: String,
    coverUrlOf: (Long) -> String,
    lastOpenedAtMap: Map<String, Long?>,
    progressMap: Map<String, Float>,
): List<LibraryItemEntity> = books.map { book ->
    val id = book.id.toString()
    LibraryItemEntity(
        id = id,
        libraryId = libraryId,
        title = book.title,
        author = book.authors.joinToString(", "),
        coverUrl = coverUrlOf(book.id),
        // Storyteller has a positions endpoint but reader-side bundle fetch and
        // progress sync come in later slices (#37/#38); for now seed with whatever
        // we've seen locally so the UI stays stable.
        readingProgress = progressMap[id] ?: 0f,
        // Readalouds are always EPUB 3 with media overlays.
        ebookFormat = EbookFormat.Epub.toStorageString(),
        ebookFileIno = null,
        description = null,
        seriesName = null,
        publishedYear = null,
        genres = "",
        publisher = null,
        lastOpenedAt = lastOpenedAtMap[id],
        addedAt = null,
        isbn = book.isbn,
        asin = book.asin,
    )
}
