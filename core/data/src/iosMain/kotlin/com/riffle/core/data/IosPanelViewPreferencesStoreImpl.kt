package com.riffle.core.data

import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed per-book Panel View toggle for iOS — Android's PanelViewPreferencesStoreImpl
// uses androidx.datastore.preferences.core.Preferences, which has no iOS equivalent. Mirrors
// IosCoverGridDensityStoreImpl's per-key StateFlow-cache pattern.
class IosPanelViewPreferencesStoreImpl : PanelViewPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()
    private val flows = mutableMapOf<String, MutableStateFlow<PanelViewPreferencesStore.State>>()

    override fun state(bookId: String): Flow<PanelViewPreferencesStore.State> = stateFor(bookId)

    override suspend fun setPanelViewOn(bookId: String, on: Boolean) {
        defaults.setBool(on, forKey = toggleKey(bookId))
        stateFor(bookId).value = PanelViewPreferencesStore.State(panelViewOn = on)
    }

    private fun stateFor(bookId: String): MutableStateFlow<PanelViewPreferencesStore.State> {
        val key = toggleKey(bookId)
        lock.lock()
        try {
            return flows.getOrPut(bookId) {
                MutableStateFlow(PanelViewPreferencesStore.State(panelViewOn = defaults.boolForKey(key)))
            }
        } finally {
            lock.unlock()
        }
    }

    private fun toggleKey(bookId: String) = "panel_view_on:$bookId"
}
