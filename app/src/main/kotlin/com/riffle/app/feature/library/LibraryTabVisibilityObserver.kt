package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source-agnostic feed of [LibraryTabVisibility] for a given library. The four optional tabs
 * (To Read, Series, Collections, Annotations) are visible iff their underlying data is non-empty
 * — no source-type or Catalog-capability gating.
 *
 * Any screen with a `libraryId` can wire tab visibility with one line via
 * [LibraryTabVisibilityViewModel]; a new source doesn't need to reinvent (or copy-paste) the
 * per-flow "is this list empty?" plumbing. `LibraryItemsViewModel` (ABS/Komga) still owns its
 * own bespoke computation because it also folds the offline/search filters into visibility —
 * that filter-awareness is server-source-specific and doesn't belong in this shared observer.
 */
@Singleton
class LibraryTabVisibilityObserver @Inject constructor(
    private val libraryObserver: LibraryObserver,
    private val toReadRepository: ToReadRepository,
    private val annotationsLibraryRepository: AnnotationsLibraryRepository,
    private val sourceRepository: SourceRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(libraryId: String): Flow<LibraryTabVisibility> {
        // "Has at least one item on the To Read list AND already in this library." Mirrors the
        // web-source `toReadItems` join — a queued id whose `library_items` row hasn't been
        // upserted yet shouldn't reveal the tab, since the tab content itself would render empty.
        val hasToRead = combine(
            toReadRepository.observeToReadItemIds(libraryId),
            libraryObserver.observeAllBooks(libraryId),
        ) { ids, all -> all.any { it.id in ids } }

        val activeSourceId: Flow<String?> = sourceRepository.observeAll()
            .map { sources -> sources.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()

        // Same query the Annotations tab content uses
        // ([com.riffle.app.feature.annotations.AnnotationsListViewModel]) so tab visibility can't
        // disagree with what the tab would render.
        val hasAnnotations = activeSourceId.flatMapLatest { sourceId ->
            if (sourceId == null) flowOf(false)
            else annotationsLibraryRepository.observeAnnotatedBooks(sourceId, libraryId)
                .map { it.isNotEmpty() }
        }

        return combine(
            hasToRead,
            libraryObserver.observeSeries(libraryId),
            libraryObserver.observeCollections(libraryId),
            hasAnnotations,
        ) { toRead, series, collections, annotations ->
            LibraryTabVisibility(
                toRead = toRead,
                series = series.isNotEmpty(),
                collections = collections.isNotEmpty(),
                annotations = annotations,
            )
        }.distinctUntilChanged()
    }
}

/**
 * Thin Hilt-scoped wrapper that reads `libraryId` from `SavedStateHandle` and exposes the
 * observer's flow as a lifecycle-bound `StateFlow`. Any screen — server-source or web-source —
 * can wire tab visibility in one line:
 *
 * ```
 * val visibility by hiltViewModel<LibraryTabVisibilityViewModel>().visibility.collectAsState()
 * ```
 */
@HiltViewModel
class LibraryTabVisibilityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observer: LibraryTabVisibilityObserver,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    val visibility: StateFlow<LibraryTabVisibility> = observer.observe(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryTabVisibility.Empty)
}
