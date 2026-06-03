package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

sealed class LibraryRefreshResult {
    data object Success : LibraryRefreshResult()
    data class NetworkError(val cause: Throwable) : LibraryRefreshResult()
    data object NoActiveServer : LibraryRefreshResult()
}

interface LibraryRepository {
    fun observeLibraries(): Flow<List<Library>>

    /** Libraries for a specific server (active or not). Reads the local DB; does not hit the network. */
    fun observeLibraries(serverId: String): Flow<List<Library>>

    fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>>
    fun observeSeries(libraryId: String): Flow<List<Series>>
    fun observeCollections(libraryId: String): Flow<List<Collection>>
    fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>>
    fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>>
    /** The active Server's copy of an item (item ids are only unique within a Server, ADR 0025). */
    suspend fun getItem(itemId: String): LibraryItem?

    /** A specific Server's copy of an item — for cross-Server callers like the Downloads screen. */
    suspend fun getItem(serverId: String, itemId: String): LibraryItem?
    suspend fun getLibrary(libraryId: String): Library?
    suspend fun markItemOpened(itemId: String)
    suspend fun updateReadingProgress(itemId: String, progress: Float)
    suspend fun refreshLibraries(): LibraryRefreshResult
    suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult
    suspend fun refreshSeries(libraryId: String): LibraryRefreshResult
    suspend fun refreshCollections(libraryId: String): LibraryRefreshResult
}
