package com.riffle.app.feature.library

import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Series
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class LibraryProjection(
    val series: List<Series>,
    val collections: List<Collection>,
    val ungrouped: List<LibraryItem>,
    val inProgress: List<LibraryItem>,
    val finished: List<LibraryItem>,
    val recentlyAdded: List<LibraryItem>,
    val continueSeries: List<LibraryItem>,
    val allBooks: List<LibraryItem>,
    val toRead: List<LibraryItem>,
    val annotations: List<AnnotationSearchResult>,
    val audiobookBookmarks: List<AudiobookBookmarkSearchResult>,
) {
    companion object {
        val Empty = LibraryProjection(
            series = emptyList(),
            collections = emptyList(),
            ungrouped = emptyList(),
            inProgress = emptyList(),
            finished = emptyList(),
            recentlyAdded = emptyList(),
            continueSeries = emptyList(),
            allBooks = emptyList(),
            toRead = emptyList(),
            annotations = emptyList(),
            audiobookBookmarks = emptyList(),
        )
    }
}

/**
 * Combines a library's source flows + UI filters into a single [LibraryProjection].
 *
 * Source flows are passed in (not observed from the repository here) so that the ViewModel's
 * existing `stateIn(WhileSubscribed)` caches are reused instead of duplicating Room cursors.
 * [libraryObserver] is only kept for the per-group offline filter, which needs to observe each
 * series'/collection's items to decide whether to drop empty groups when offline.
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

    // When a query is active, search all items (including those in series/collections) so that
    // books are findable by title/author regardless of grouping.
    // When offline, only items available locally (downloaded or cached) are shown.
    private val ungroupedProjection: Flow<List<LibraryItem>> =
        combine(ungroupedSource, allItemsSource, searchQuery, isOffline) { ungrouped, all, query, offline ->
            val base = if (query.isEmpty()) ungrouped
                else all.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
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
        combine(allBooksSource, isOffline, notStartedFilterActive) { items, offline, notStartedOnly ->
            val afterOffline = if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
            if (notStartedOnly) afterOffline.filter { it.readingProgress == 0f } else afterOffline
        }

    private val toReadProjection: Flow<List<LibraryItem>> =
        combine(toReadIdsSource, allBooksSource, isOffline) { ids, all, offline ->
            val byId = all.associateBy { it.id }
            val items = ids.mapNotNull { byId[it] }
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }

    // Annotation search results, scoped to this library's items and matched on note text,
    // highlighted snippet, or bookmark title. Independent of the Books section (a book is never
    // promoted here because its annotations matched). Empty when the query is blank.
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

    // Audiobook bookmark search results, scoped to this library's items and matched on title.
    // Shares the same Annotations section in the UI. Empty when the query is blank.
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
    }

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
