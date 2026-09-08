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
    fun lastOpenedLibrary(sourceId: String): Flow<String?>
    suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String)

    /** Whether Riffle was the last active top-level destination. */
    fun wasRiffleLastActive(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    /** Mark Riffle as the last active destination. Cleared by [setLastOpenedLibrary] and [clearRiffleActive]. */
    suspend fun setRiffleActive() {}

    /** Clear the Riffle-last-active flag without setting a library. Used on source switch. */
    suspend fun clearRiffleActive() {}
}
