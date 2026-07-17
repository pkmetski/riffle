package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.riffle.core.data.di.PanelViewPreferencesDataStore
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed per-book Panel View toggle. Key is `<bookId>` — two books never collide
 * because bookId is the canonical `(sourceId, itemId)` composite (ADR 0025).
 */
class PanelViewPreferencesStoreImpl @Inject constructor(
    @param:PanelViewPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : PanelViewPreferencesStore {

    override fun state(bookId: String): Flow<PanelViewPreferencesStore.State> =
        dataStore.data.map { prefs ->
            PanelViewPreferencesStore.State(panelViewOn = prefs[toggleKey(bookId)] ?: false)
        }

    override suspend fun setPanelViewOn(bookId: String, on: Boolean) {
        dataStore.edit { prefs ->
            if (on) prefs[toggleKey(bookId)] = true
            else prefs.remove(toggleKey(bookId))
        }
    }

    private fun toggleKey(bookId: String) = booleanPreferencesKey("panel_view_on:$bookId")
}
