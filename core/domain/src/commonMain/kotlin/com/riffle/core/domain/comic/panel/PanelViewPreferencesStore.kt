package com.riffle.core.domain.comic.panel

import kotlinx.coroutines.flow.Flow

/**
 * Per-book, per-device Panel View toggle (ADR 0043). Nothing here is synced across devices; a
 * book re-opens in the last-used mode on the same device only. Panel index is per-session
 * transient — the reader always opens at panel 0.
 */
interface PanelViewPreferencesStore {

    data class State(val panelViewOn: Boolean = false)

    fun state(bookId: String): Flow<State>

    suspend fun setPanelViewOn(bookId: String, on: Boolean)
}
