package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

data class LibraryFilterPreferences(
    val selectedFacetKey: String? = null,
    val notStartedFilterActive: Boolean = false,
    val sortModeName: String? = null,
)

/**
 * Device-local browse/filter state scoped to a concrete source library.
 *
 * The same source can expose multiple library roots (Chitanka books and Gramofonche audiobooks),
 * so every key includes both ids. Values are display preferences, not synced content state.
 */
interface LibraryFilterPreferencesStore {
    fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences>
    suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?)
    suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean)
    suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?)
}
