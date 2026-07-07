package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface LibraryVisibilityPreferencesStore {
    fun hiddenLibraryIds(sourceId: String): Flow<Set<String>>
    suspend fun hideLibrary(sourceId: String, libraryId: String)
    suspend fun showLibrary(sourceId: String, libraryId: String)
}
