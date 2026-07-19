package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import com.riffle.core.models.Collection
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series

sealed class LibraryRefreshResult {
    data object Success : LibraryRefreshResult()
    data class NetworkError(val cause: Throwable) : LibraryRefreshResult()
    data object NoActiveServer : LibraryRefreshResult()
}

/**
 * Read-only view of the local library cache. Resolves the active Server internally and emits an
 * empty list / null when there is none. ViewModels that only show library data depend on this
 * narrow surface — they do not pull in refresh or push concerns.
 */
interface LibraryObserver {
    fun observeLibraries(): Flow<List<Library>>

    /** Libraries for a specific server (active or not). Reads the local DB; does not hit the network. */
    fun observeLibraries(sourceId: String): Flow<List<Library>>

    fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>>
    fun observeSeries(libraryId: String): Flow<List<Series>>
    fun observeCollections(libraryId: String): Flow<List<Collection>>
    fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>>
    fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>>
    fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>>

    /** The active Server's copy of an item (item ids are only unique within a Server, ADR 0025). */
    suspend fun getItem(itemId: String): LibraryItem?

    /**
     * Reactive view of the active Server's copy of an item. Re-emits when the row changes — notably
     * when the reader persists new readingProgress on close — so screens stay current instead of
     * showing a one-shot snapshot taken before the user read.
     */
    fun observeItem(itemId: String): Flow<LibraryItem?>

    /** A specific Server's copy of an item — for cross-Server callers like the Downloads screen. */
    suspend fun getItem(sourceId: String, itemId: String): LibraryItem?
    suspend fun getLibrary(libraryId: String): Library?

    /** The id of the series the given item belongs to, or null if it is not in any series. */
    suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String?
}

/**
 * Local mutators on the library cache — pure DAO writes against the active Server's copy. The
 * cross-cutting push (sync to ABS / progress reconciliation) belongs in a use-case wrapping this.
 */
interface LibraryMutator {
    /** Bumps lastOpenedAt for the active server's item. No network push. */
    suspend fun markItemOpened(itemId: String)

    /** Writes the new readingProgress to the active server's local row. No network push. */
    suspend fun updateReadingProgress(itemId: String, progress: Float)

    /** Writes the new readingProgress to a specific server's local row. Cross-server callers. */
    suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float)
}

/**
 * Talks to the network and refreshes the local cache. No matcher/syncer cross-cutting — that
 * choreography lives in [com.riffle.core.domain.usecase.RefreshLibraryItems].
 */
interface LibraryRefresher {
    suspend fun refreshLibraries(): LibraryRefreshResult
    suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult
    suspend fun refreshSeries(libraryId: String): LibraryRefreshResult
    suspend fun refreshCollections(libraryId: String): LibraryRefreshResult
}
