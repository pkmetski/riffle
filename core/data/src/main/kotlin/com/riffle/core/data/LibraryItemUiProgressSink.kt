package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.UiProgressSink
import javax.inject.Inject

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
 */
class LibraryItemUiProgressSink @Inject constructor(
    private val libraryItemDao: LibraryItemDao,
) : UiProgressSink {
    override suspend fun apply(sourceId: String, itemId: String, readingProgress: Float, finishedAt: Long?) {
        libraryItemDao.updateReadingProgress(sourceId, itemId, readingProgress)
        libraryItemDao.updateFinishedAt(sourceId, itemId, finishedAt)
    }
}
