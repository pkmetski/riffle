package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Per-server, device-local memory of the library the user last opened. Sibling to
 * [LibraryOrderPreferencesStore] and [LibraryVisibilityPreferencesStore]: it is a personal display
 * preference and is never synced.
 *
 * On app start the active server's remembered library is reopened (falling back to the first visible
 * library when nothing is remembered, or when the remembered library is now hidden or gone).
 */
interface LastOpenedLibraryStore {
    fun lastOpenedLibrary(serverId: String): Flow<String?>
    suspend fun setLastOpenedLibrary(serverId: String, libraryId: String)
}
