package com.riffle.core.data

import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import kotlinx.coroutines.flow.Flow

class PanelViewPreferencesStoreImpl(private val store: KeyValueStore) : PanelViewPreferencesStore {

    override fun state(bookId: String): Flow<PanelViewPreferencesStore.State> =
        store.observe { PanelViewPreferencesStore.State(panelViewOn = store.getBoolean(toggleKey(bookId), false)) }

    override suspend fun setPanelViewOn(bookId: String, on: Boolean) {
        if (on) store.setBoolean(toggleKey(bookId), true) else store.remove(toggleKey(bookId))
    }

    private fun toggleKey(bookId: String) = "panel_view_on:$bookId"
}
