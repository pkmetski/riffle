package com.riffle.shared.library

import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private fun sortAllBooks(items: List<LibraryItem>, mode: LibrarySortMode): List<LibraryItem> {
    val byTitle: Comparator<LibraryItem> = compareBy { it.title.lowercase() }
    return when (mode) {
        LibrarySortMode.TITLE_ASC -> items.sortedWith(byTitle)
        LibrarySortMode.TITLE_DESC -> items.sortedWith(byTitle.reversed())
        LibrarySortMode.AUTHOR_ASC -> items.sortedWith(
            compareBy<LibraryItem> { it.author.lowercase() }.then(byTitle),
        )
        LibrarySortMode.ADDED_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { (it.addedAt ?: 0L) > 0L }
                .thenByDescending { it.addedAt ?: 0L }
                .then(byTitle),
        )
        LibrarySortMode.ADDED_ASC -> items.sortedWith(
            compareByDescending<LibraryItem> { (it.addedAt ?: 0L) > 0L }
                .thenBy { it.addedAt ?: Long.MAX_VALUE }
                .then(byTitle),
        )
        LibrarySortMode.RECENTLY_OPENED -> items.sortedWith(
            compareByDescending<LibraryItem> { it.lastOpenedAt != null }
                .thenByDescending { it.lastOpenedAt ?: 0L }
                .then(byTitle),
        )
    }
}

/**
 * Combines a library's source flows + UI filters into a single [LibraryProjection].
 *
 * Source flows are passed in (not observed from the repository here) so that the ViewModel's
 * existing `stateIn(WhileSubscribed)` caches are reused instead of duplicating cursors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFilterEngine(
    private val libraryObserver: LibraryObserver,
    private val annotationStore: AnnotationStore,
    private val audiobookBookmarkStore: AudiobookBookmarkStore,
    private val offlineAvailability: LibraryItemOfflineAvailability,
    seriesSource: Flow<List<Series>>,
    collectionsSource: Flow<List<Collection>>,
    ungroupedSource: Flow<List<LibraryItem>>,
    inProgressSource: Flow<List<LibraryItem>>,
    finishedSource: Flow<List<LibraryItem>>,
    recentlyAddedSource: Flow<List<LibraryItem>>,
    continueSeriesSource: Flow<List<LibraryItem>>,
    allBooksSource: Flow<List<LibraryItem>>,
    allItemsSource: Flow<List<LibraryItem>>,
    toReadIdsSource: Flow<Set<String>>,
    isOffline: Flow<Boolean>,
    searchQuery: Flow<String>,
    notStartedFilterActive: Flow<Boolean>,
    librarySortMode: Flow<LibrarySortMode>,
    private val computeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {

    private val seriesProjection: Flow<List<Series>> =
        combine(seriesSource, searchQuery, isOffline) { list, query, offline ->
            Triple(list, query, offline)
        }.flatMapLatest { (list, query, offline) ->
            val queryFiltered = if (query.isEmpty()) list else list.filter { it.name.contains(query, ignoreCase = true) }
            filterSeriesOffline(queryFiltered, offline)
        }

    private val collectionsProjection: Flow<List<Collection>> =
        combine(collectionsSource, searchQuery, isOffline) { list, query, offline ->
            Triple(list, query, offline)
        }.flatMapLatest { (list, query, offline) ->
            val queryFiltered = if (query.isEmpty()) list else list.filter { it.name.contains(query, ignoreCase = true) }
            filterCollectionsOffline(queryFiltered, offline)
        }

    private val ungroupedProjection: Flow<List<LibraryItem>> =
        combine(ungroupedSource, allItemsSource, searchQuery, isOffline) { ungrouped, all, query, offline ->
            val base = if (query.isEmpty()) {
                ungrouped
            } else {
                all.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
            }
            if (offline) base.filter { offlineAvailability.isAvailableOffline(it) } else base
        }

    private val inProgressProjection: Flow<List<LibraryItem>> =
        combine(inProgressSource, isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }

    private val finishedProjection: Flow<List<LibraryItem>> =
        combine(finishedSource, isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }

    private val recentlyAddedProjection: Flow<List<LibraryItem>> =
        combine(recentlyAddedSource, isOffline) { items, offline ->
            val filtered = if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
            filtered.take(50)
        }

    private val continueSeriesProjection: Flow<List<LibraryItem>> =
        combine(continueSeriesSource, isOffline) { items, offline ->
            val filtered = if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
            filtered.take(20)
        }

    private val allBooksProjection: Flow<List<LibraryItem>> =
        combine(allBooksSource, isOffline, notStartedFilterActive, librarySortMode) { items, offline, notStartedOnly, sort ->
            val afterOffline = if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
            val afterNotStarted = if (notStartedOnly) afterOffline.filter { it.readingProgress == 0f } else afterOffline
            sortAllBooks(afterNotStarted, sort)
        }

    private val toReadProjection: Flow<List<LibraryItem>> =
        combine(toReadIdsSource, allBooksSource, isOffline) { ids, all, offline ->
            val byId = all.associateBy { it.id }
            val items = ids.mapNotNull { byId[it] }
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }

    private val annotationsProjection: Flow<List<AnnotationSearchResult>> =
        combine(allItemsSource, searchQuery) { items, query -> items to query }
            .flatMapLatest { (items, query) ->
                val sourceId = items.firstOrNull()?.sourceId
                if (query.isBlank() || sourceId.isNullOrEmpty()) {
                    flowOf(emptyList())
                } else {
                    annotationStore.observeAnnotationsForSource(sourceId)
                        .map { annotations -> searchAnnotations(annotations, items, query) }
                }
            }

    private val audiobookBookmarksProjection: Flow<List<AudiobookBookmarkSearchResult>> =
        combine(allItemsSource, searchQuery) { items, query -> items to query }
            .flatMapLatest { (items, query) ->
                val sourceId = items.firstOrNull()?.sourceId
                if (query.isBlank() || sourceId.isNullOrEmpty()) {
                    flowOf(emptyList())
                } else {
                    audiobookBookmarkStore.observeForSource(sourceId)
                        .map { bookmarks -> searchAudiobookBookmarks(bookmarks, items, query) }
                }
            }

    @Suppress("UNCHECKED_CAST")
    val projection: Flow<LibraryProjection> = combine(
        seriesProjection,
        collectionsProjection,
        ungroupedProjection,
        inProgressProjection,
        finishedProjection,
        recentlyAddedProjection,
        continueSeriesProjection,
        allBooksProjection,
        toReadProjection,
        annotationsProjection,
        audiobookBookmarksProjection,
    ) { values ->
        LibraryProjection(
            series = values[0] as List<Series>,
            collections = values[1] as List<Collection>,
            ungrouped = values[2] as List<LibraryItem>,
            inProgress = values[3] as List<LibraryItem>,
            finished = values[4] as List<LibraryItem>,
            recentlyAdded = values[5] as List<LibraryItem>,
            continueSeries = values[6] as List<LibraryItem>,
            allBooks = values[7] as List<LibraryItem>,
            toRead = values[8] as List<LibraryItem>,
            annotations = values[9] as List<AnnotationSearchResult>,
            audiobookBookmarks = values[10] as List<AudiobookBookmarkSearchResult>,
        )
    }.flowOn(computeDispatcher)

    private fun filterCollectionsOffline(collections: List<Collection>, offline: Boolean): Flow<List<Collection>> {
        if (!offline || collections.isEmpty()) return flowOf(collections)
        return combine(collections.map { col -> libraryObserver.observeCollectionItems(col.id) }) { itemArrays ->
            collections.zip(itemArrays.toList())
                .filter { (_, items) -> items.any { offlineAvailability.isAvailableOffline(it) } }
                .map { (col, _) -> col }
        }
    }

    private fun filterSeriesOffline(series: List<Series>, offline: Boolean): Flow<List<Series>> {
        if (!offline || series.isEmpty()) return flowOf(series)
        return combine(series.map { s -> libraryObserver.observeSeriesItems(s.id) }) { itemArrays ->
            series.zip(itemArrays.toList())
                .filter { (_, items) -> items.any { offlineAvailability.isAvailableOffline(it) } }
                .map { (s, _) -> s }
        }
    }
}
