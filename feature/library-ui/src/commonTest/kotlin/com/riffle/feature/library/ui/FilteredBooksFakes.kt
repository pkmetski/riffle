package com.riffle.feature.library.ui

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Collection
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Minimal collaborators for driving a real `FilteredBooksViewModel` in a render test. */
internal object FilteredBooksFakes {

    fun libraryObserver(items: List<LibraryItem>): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(items)
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
    }

    fun sourceRepository(): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            throw NotImplementedError()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    fun tokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    fun offlineAvailability(available: Set<String>): LibraryItemOfflineAvailability =
        object : LibraryItemOfflineAvailability {
            override fun isAvailableOffline(item: LibraryItem): Boolean = item.id in available
        }

    fun connectivityObserver(online: Boolean): ConnectivityObserver = object : ConnectivityObserver {
        private val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> get() = state
    }

    fun readaloudLinkRepository(linked: Set<String> = emptySet()): ReadaloudLinkRepository =
        object : ReadaloudLinkRepository {
            override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
            override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(linked)
            override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
            override suspend fun findByStorytellerBook(
                storytellerSourceId: String,
                storytellerBookId: String,
            ): List<ReadaloudLink> = emptyList()

            override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
            override suspend fun countForSource(sourceId: String): Int = 0
        }
}
