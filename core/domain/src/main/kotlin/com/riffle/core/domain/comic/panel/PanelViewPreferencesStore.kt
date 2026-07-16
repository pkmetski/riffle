package com.riffle.core.domain.comic.panel

import kotlinx.coroutines.flow.Flow

/**
 * Per-book, per-device Panel View state (ADR 0043):
 *  - `panelViewOn` — the reader's toggle, remembered so a book re-opens in the last-used mode.
 *  - `(lastPageIndex, lastPanelIndex)` — same-device resume marker; the panel index is only
 *    honoured when [lastPageIndex] matches the resume page.
 *
 * Nothing here is synced across devices. When [Progress Sync] pulls a newer page from another
 * peer the caller invokes [rememberPositionForResume] with the new page, which discards any
 * stale panel index (remote wins the page; local panel state is discarded).
 */
interface PanelViewPreferencesStore {

    data class State(
        val panelViewOn: Boolean = false,
        val lastPageIndex: Int? = null,
        val lastPanelIndex: Int? = null,
    ) {
        /** Panel index to resume at when opening on [currentPage]. Zero if the pages disagree. */
        fun panelIndexForPage(currentPage: Int): Int =
            if (lastPageIndex == currentPage) (lastPanelIndex ?: 0) else 0
    }

    fun state(bookId: String): Flow<State>

    suspend fun setPanelViewOn(bookId: String, on: Boolean)

    /**
     * Persist the current reader position so that reopening the same book on the same device
     * resumes at [pageIndex] / [panelIndex]. Called every time the reader lands on a new panel.
     */
    suspend fun rememberPositionForResume(bookId: String, pageIndex: Int, panelIndex: Int)

    /**
     * Drop cached panel resume when Progress Sync jumps to a different page. Panel View toggle
     * state is preserved; only the panel-index memory is cleared.
     */
    suspend fun clearPanelResume(bookId: String)
}
