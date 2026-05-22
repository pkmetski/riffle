package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface LibraryVisibilityPreferencesStore {
    fun hiddenLibraryIds(serverId: String): Flow<Set<String>>
    suspend fun hideLibrary(serverId: String, libraryId: String)
    suspend fun showLibrary(serverId: String, libraryId: String)
}
