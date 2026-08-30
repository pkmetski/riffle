package com.riffle.core.data

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.UiProgressSink

/**
 * Room-backed [UiProgressSink] the sweep invokes after a ServerWon reconcile so the library grid
 * and detail view (which observe `library_items.readingProgress` and `finishedAt`) re-emit
 * without waiting for the reader to reopen and derive them from the locator on close.
 *
 * The position stores (`reading_positions`, `audiobook_positions`) keep only locators / seconds;
 * before this sink existed a background sweep would land a fresh server position but leave the
 * UI columns stale — the cover kept the old blue bar and % until the book was actually opened.
 * `finishedAt` is passed through nullable so a server "no longer finished" state clears the local
 * stamp too (server-wins, matching the ServerWon branch in [com.riffle.core.domain.ProgressReconciler]).
 *
 * For web sources (Chitanka, Gutenberg), a synced position can arrive on a device that has never
 * opened the book — `library_items` has no row yet. In that case this sink materializes the row
 * by fetching item metadata from the catalog before writing the progress columns, so the library
 * grid shows the book and its progress bar without requiring the user to open it first.
 */
class LibraryItemUiProgressSink constructor(
    private val libraryItemDao: LibraryItemDao,
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val upserter: WebSourceLibraryItemUpserter,
) : UiProgressSink {
    override suspend fun apply(sourceId: String, itemId: String, readingProgress: Float, finishedAt: Long?) {
        if (libraryItemDao.getById(sourceId, itemId) == null) {
            runCatching {
                val source = sourceRepository.getById(sourceId) ?: return@runCatching
                if (!source.type.isWebSource) return@runCatching
                val catalog = catalogRegistry.forSourceId(sourceId) ?: return@runCatching
                val item = catalog.getItem(itemId) ?: return@runCatching
                upserter.upsert(sourceId, item)
            }
        }
        libraryItemDao.updateReadingProgress(sourceId, itemId, readingProgress)
        libraryItemDao.updateFinishedAt(sourceId, itemId, finishedAt)
    }
}
