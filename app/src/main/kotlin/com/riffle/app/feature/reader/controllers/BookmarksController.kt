package com.riffle.app.feature.reader.controllers

import com.riffle.core.domain.normalizeEpubHref
import com.riffle.app.feature.reader.session.OrchestratorScope
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ReaderOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator
import com.riffle.feature.reader.bookmarkEpsFor

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
class BookmarksController constructor(
    private val scope: OrchestratorScope,
    private val annotationStore: AnnotationStore,
    private val onScheduleSync: () -> Unit,
) {

    fun interface Factory {
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

    private val _spinePositionCounts = MutableStateFlow<Pair<List<String>, List<Int>>>(emptyList<String>() to emptyList())

    /**
     * Live per-chapter `viewportSize / chapterSize` — the exact geometric bound for a
     * viewport-midpoint locator (see [bookmarkEpsFor]). Empty until each mode's producer
     * measures a chapter; missing entries fall through to [_spinePositionCounts] and then to
     * the flat eps constants. Populated by the VM from `ReaderPresenter.viewportFractionEvents`
     * and MUST only emit on measurement/size events, never on scroll (see issue #399 and the
     * distinct-until-changed discipline in [EpubReaderViewModel.putViewportFraction]).
     */
    private val _viewportFractionByHref = MutableStateFlow<Map<String, Double>>(emptyMap())

    /**
     * Current reader orientation, read non-reactively inside [isCurrentPageBookmarked]'s combine
     * so the combine stays 2-arg (matches master's shape and avoids the Eagerly-shared
     * viewModelScope chain that flaked the orientation-flip harness). Mode changes call
     * [onOrientationChanged] which republishes the locator to force a recompute. `@Volatile`
     * because the VM updates it from any dispatcher.
     */
    @Volatile
    private var currentOrientation: ReaderOrientation = ReaderOrientation.Horizontal

    /**
     * True when one of this item's bookmarks falls inside the reader's currently visible viewport.
     *
     * The ±eps window comes from [bookmarkEpsFor], which prefers the live per-chapter
     * `viewportSize / chapterSize` reported by the active renderer (issue #399), then Readium's
     * `0.5 / positionsInChapter` approximation, then a flat fallback. All three modes emit a
     * viewport-midpoint (`ContinuousPositionTracker.locatorAt`) or content-top locator whose
     * anchor is visible iff `|current - saved| ≤ eps`.
     *
     * The `<= eps` (not `< eps`) makes the boundary case inclusive — when the bookmark anchor
     * sits exactly at the viewport's top edge, a strict `<` would silently call that OFF even
     * though the anchor is visible.
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
        _bookmarkPositions,
        _currentLocator,
        _spinePositionCounts,
        _viewportFractionByHref,
    ) { bookmarksForChapter, locator, spineCounts, fractions ->
        val href = locator?.href?.toString() ?: return@combine false
        val prog = locator.locations.progression
        val hrefNorm = normalizeEpubHref(href)
        val eps = bookmarkEpsFor(currentOrientation, spineCounts, fractions, hrefNorm)
        bookmarksForChapter.any { bm ->
            bm.chapterHref == hrefNorm &&
                (prog == null || kotlin.math.abs(bm.progression - prog) <= eps)
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The progression window used to decide whether a stored bookmark falls on the current
     * viewport. Public so the toggle-bookmark call site in `EpubReaderViewModel` uses the SAME
     * band the indicator does — a mismatch there would toggle the wrong (nearby) bookmark
     * instead of creating a new one on the current page.
     */
    fun bookmarkEpsFor(chapterHref: String): Double =
        bookmarkEpsFor(
            currentOrientation,
            _spinePositionCounts.value,
            _viewportFractionByHref.value,
            normalizeEpubHref(chapterHref),
        )

    private var observeJob: Job? = null
    private var locatorJob: Job? = null
    private var positionsJob: Job? = null
    private var viewportFractionJob: Job? = null

    /**
     * Bind to a specific book. Cancels any previous observation and starts fresh.
     *
     * [currentLocator] — the live VM locator so [isCurrentPageBookmarked] stays reactive.
     */
    fun bind(
        sourceId: String,
        itemId: String,
        currentLocator: StateFlow<Locator?>,
        spinePositionCounts: StateFlow<Pair<List<String>, List<Int>>>,
        viewportFractionByHref: StateFlow<Map<String, Double>>,
    ) {
        observeJob?.cancel()
        locatorJob?.cancel()
        positionsJob?.cancel()
        viewportFractionJob?.cancel()
        _bookmarkPositions.value = emptyList()
        _currentLocator.value = null
        _spinePositionCounts.value = emptyList<String>() to emptyList()
        _viewportFractionByHref.value = emptyMap()

        observeJob = scope.launch {
            annotationStore.observeBookmarks(sourceId, itemId).collect { annotations ->
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
        positionsJob = scope.launch {
            spinePositionCounts.collect { counts ->
                _spinePositionCounts.value = counts
            }
        }
        viewportFractionJob = scope.launch {
            viewportFractionByHref.collect { fractions ->
                _viewportFractionByHref.value = fractions
            }
        }
    }

    /**
     * Set the active reader orientation. Re-publishes the current locator so the
     * [isCurrentPageBookmarked] combine re-evaluates with the new eps. Called from the VM
     * whenever the user changes orientation; safe to call from any dispatcher.
     */
    fun onOrientationChanged(orientation: ReaderOrientation) {
        if (currentOrientation == orientation) return
        currentOrientation = orientation
        // Trigger a recompute by republishing the same locator instance. The combine's identity
        // check on StateFlow swallows no-op .value = sameRef, so reassign via a fresh "no-op"
        // emission — use a temporary null transit if non-null, else nothing to recompute.
        val locator = _currentLocator.value ?: return
        _currentLocator.value = null
        _currentLocator.value = locator
    }

    /** Rename a bookmark; schedules annotation sync. */
    fun renameBookmark(id: String, title: String) {
        scope.launch {
            annotationStore.renameBookmark(id, title)
            onScheduleSync()
        }
    }

}
