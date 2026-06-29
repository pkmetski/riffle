package com.riffle.app.feature.reader.controllers

import com.riffle.app.feature.reader.normalizeEpubHref
import com.riffle.app.feature.reader.session.OrchestratorScope
import com.riffle.core.domain.AnnotationStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Owns bookmark observation and page-bookmarked detection. Lifted from EpubReaderViewModel
 * as part of the VM split (#303).
 *
 * Note: [toggleBookmark] requires CFI building + chapter-title resolution that depends on
 * [org.readium.r2.shared.publication.Publication] and rail segment state still living in the VM.
 * That method will be lifted to AnnotationSession (Task 7) once both deps are orchestrated.
 * The controller owns the observation/detection half, which is independently useful and testable.
 *
 * MUST NOT import android.webkit.* or ContinuousReaderView.
 */
class BookmarksController @AssistedInject constructor(
    @Assisted private val scope: OrchestratorScope,
    private val annotationStore: AnnotationStore,
    @Assisted private val onScheduleSync: () -> Unit,
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, onScheduleSync: () -> Unit): BookmarksController
    }

    /** A persisted bookmark decoded to its chapter position for page-level indicator matching. */
    data class BookmarkPosition(
        val id: String,
        val chapterHref: String,
        val progression: Double,
    )

    private val _bookmarkPositions = MutableStateFlow<List<BookmarkPosition>>(emptyList())
    val bookmarkPositions: StateFlow<List<BookmarkPosition>> = _bookmarkPositions

    private val _currentLocator = MutableStateFlow<Locator?>(null)

    /**
     * True when one of this item's bookmarks falls on the reader's current page
     * (chapter href + within-chapter progression within BOOKMARK_PAGE_EPS = 5%).
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
        _bookmarkPositions,
        _currentLocator,
    ) { positions, locator ->
        val href = locator?.href?.toString() ?: return@combine false
        val prog = locator.locations.progression
        val hrefNorm = normalizeEpubHref(href)
        positions.any { bm ->
            bm.chapterHref == hrefNorm &&
                (prog == null || kotlin.math.abs(bm.progression - prog) < BOOKMARK_PAGE_EPS)
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var observeJob: Job? = null
    private var locatorJob: Job? = null

    /**
     * Bind to a specific book. Cancels any previous observation and starts fresh.
     * [currentLocator] is the live VM locator StateFlow so [isCurrentPageBookmarked] stays reactive.
     */
    fun bind(
        serverId: String,
        itemId: String,
        currentLocator: StateFlow<Locator?>,
    ) {
        observeJob?.cancel()
        locatorJob?.cancel()
        _bookmarkPositions.value = emptyList()
        _currentLocator.value = null

        observeJob = scope.launch {
            annotationStore.observeBookmarks(serverId, itemId).collect { annotations ->
                _bookmarkPositions.value = annotations.map { a ->
                    BookmarkPosition(
                        id = a.id,
                        chapterHref = normalizeEpubHref(a.chapterHref),
                        progression = a.progression,
                    )
                }
            }
        }
        locatorJob = scope.launch {
            currentLocator.collect { locator ->
                _currentLocator.value = locator
            }
        }
    }

    /** Rename a bookmark; schedules annotation sync. */
    fun renameBookmark(id: String, title: String) {
        scope.launch {
            annotationStore.renameBookmark(id, title)
            onScheduleSync()
        }
    }

    companion object {
        // ±5% within-chapter progression window for page-bookmark detection.
        internal const val BOOKMARK_PAGE_EPS = 0.05
    }
}
