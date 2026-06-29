package com.riffle.app.feature.reader.controllers

import com.riffle.app.feature.reader.normalizeEpubHref
import com.riffle.app.feature.reader.session.OrchestratorScope
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ReaderOrientation
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
    private val _currentOrientation = MutableStateFlow(ReaderOrientation.Horizontal)

    /**
     * True when one of this item's bookmarks falls on the reader's current page.
     *
     * Bookmark `progression` is stored in content-top semantics (the chapter-relative position
     * of the bookmark's CFI element, derived in `EpubReaderViewModel.toggleBookmark`). Readium
     * paginated and vertical modes emit `locator.locations.progression` in the same content-top
     * frame, so a tight ±5% window matches the current page exactly.
     *
     * Continuous mode emits the locator's progression at the VIEWPORT MIDPOINT
     * (`ContinuousPositionTracker.locatorAt`), which sits roughly `viewportFraction/2` past the
     * stored content-top progression. A 5% window would miss a bookmark whose anchor is at the
     * top of the viewport — the case the user actually expects to register as "this page is
     * bookmarked". Widen the window to `BOOKMARK_VIEWPORT_EPS` (≈25%) in continuous so the
     * indicator activates whenever the bookmark anchor is within the visible viewport. The wider
     * window has a small false-positive cost when two bookmarks land within ~25% of one chapter,
     * which we accept — the trade-off favours the navigation/save symmetry the user sees.
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
        _bookmarkPositions,
        _currentLocator,
        _currentOrientation,
    ) { positions, locator, orientation ->
        val href = locator?.href?.toString() ?: return@combine false
        val prog = locator.locations.progression
        val hrefNorm = normalizeEpubHref(href)
        val eps = if (orientation == ReaderOrientation.Continuous) BOOKMARK_VIEWPORT_EPS
        else BOOKMARK_PAGE_EPS
        positions.any { bm ->
            bm.chapterHref == hrefNorm &&
                (prog == null || kotlin.math.abs(bm.progression - prog) < eps)
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var observeJob: Job? = null
    private var locatorJob: Job? = null
    private var orientationJob: Job? = null

    /**
     * Bind to a specific book. Cancels any previous observation and starts fresh.
     * [currentLocator] is the live VM locator StateFlow so [isCurrentPageBookmarked] stays reactive.
     * [currentOrientation] selects the eps window — see [isCurrentPageBookmarked] for the
     * continuous-mode rationale.
     */
    fun bind(
        serverId: String,
        itemId: String,
        currentLocator: StateFlow<Locator?>,
        // Defaults to a Horizontal-only flow so existing call sites (and tests) that don't care
        // about the continuous-mode widened eps still compile; the production VM passes a real
        // flow tracking the user's chosen orientation.
        currentOrientation: StateFlow<ReaderOrientation> = MutableStateFlow(ReaderOrientation.Horizontal),
    ) {
        observeJob?.cancel()
        locatorJob?.cancel()
        orientationJob?.cancel()
        _bookmarkPositions.value = emptyList()
        _currentLocator.value = null
        _currentOrientation.value = currentOrientation.value

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
        orientationJob = scope.launch {
            currentOrientation.collect { orientation ->
                _currentOrientation.value = orientation
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
        // ±5% within-chapter progression window for paginated / vertical mode — both emit
        // content-top progression and the bookmark stores the same, so a tight match is correct.
        internal const val BOOKMARK_PAGE_EPS = 0.05

        // ±25% within-chapter window for continuous mode, where the locator's progression sits at
        // the viewport midpoint while the bookmark stores its CFI's content-top progression. The
        // widened window catches bookmarks anchored anywhere in the current viewport without
        // chasing exact midpoint equality — see [isCurrentPageBookmarked] for the full rationale.
        internal const val BOOKMARK_VIEWPORT_EPS = 0.25
    }
}
