package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.riffle.core.data.di.PanelViewPreferencesDataStore
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed per-book Panel View state. Keys are `<bookId>` + suffix; two books never
 * collide because bookId is the canonical `(sourceId, itemId)` composite (ADR 0025).
 */
class PanelViewPreferencesStoreImpl @Inject constructor(
    @param:PanelViewPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : PanelViewPreferencesStore {

    override fun state(bookId: String): Flow<PanelViewPreferencesStore.State> =
        dataStore.data.map { prefs ->
            PanelViewPreferencesStore.State(
                panelViewOn = prefs[toggleKey(bookId)] ?: false,
                lastPageIndex = prefs[lastPageKey(bookId)],
                lastPanelIndex = prefs[lastPanelKey(bookId)],
            )
        }

    override suspend fun setPanelViewOn(bookId: String, on: Boolean) {
        dataStore.edit { prefs ->
            if (on) prefs[toggleKey(bookId)] = true
            else prefs.remove(toggleKey(bookId))
        }
    }

    override suspend fun rememberPositionForResume(bookId: String, pageIndex: Int, panelIndex: Int) {
        dataStore.edit { prefs ->
            prefs[lastPageKey(bookId)] = pageIndex
            prefs[lastPanelKey(bookId)] = panelIndex
        }
    }

    override suspend fun clearPanelResume(bookId: String) {
        dataStore.edit { prefs ->
            prefs.remove(lastPageKey(bookId))
            prefs.remove(lastPanelKey(bookId))
        }
    }

    private fun toggleKey(bookId: String) = booleanPreferencesKey("panel_view_on:$bookId")
    private fun lastPageKey(bookId: String) = intPreferencesKey("panel_last_page:$bookId")
    private fun lastPanelKey(bookId: String) = intPreferencesKey("panel_last_panel:$bookId")
}
