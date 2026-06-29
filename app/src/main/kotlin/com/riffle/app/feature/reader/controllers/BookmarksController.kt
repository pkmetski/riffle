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
    private val _continuousViewportFraction = MutableStateFlow<Float?>(null)

    /**
     * True when one of this item's bookmarks falls inside the reader's currently visible viewport.
     *
     * Bookmark `progression` is whatever the locator emitted at save-time:
     *  - **Readium (paginated + vertical):** content-top progression. Bookmark anchor and
     *    current locator share the same frame, so a tight ±5% window is the correct match.
     *  - **Continuous:** viewport-midpoint progression (`ContinuousPositionTracker.locatorAt`).
     *    Bookmark anchor pixel y = `M_save * slot.height`; the anchor is visible iff
     *    `top_progression ≤ M_save ≤ bottom_progression`. Equivalently (rearranging),
     *    `|midpoint_now - M_save| < viewportFraction/2`. We use the live `viewportFraction`
     *    plumbed in from `ContinuousReaderView` so the window is the *actual* visible range,
     *    not a fixed guess that's too tight for short chapters and too loose for long ones.
     *    Falls back to a conservative [BOOKMARK_VIEWPORT_EPS] when the fraction hasn't arrived
     *    yet (no scroll events since open).
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
        _bookmarkPositions,
        _currentLocator,
        _currentOrientation,
        _continuousViewportFraction,
    ) { positions, locator, orientation, viewportFraction ->
        val href = locator?.href?.toString() ?: return@combine false
        val prog = locator.locations.progression
        val hrefNorm = normalizeEpubHref(href)
        val eps = when {
            orientation != ReaderOrientation.Continuous -> BOOKMARK_PAGE_EPS
            // `viewportFraction / 2` is the geometric minimum that covers a viewport's worth of
            // chapter. Add a [BOOKMARK_PAGE_EPS] slack on top to absorb the per-chapter anchor
            // offset (heading elements rarely sit at pixel-0 of the slot — typically there's a
            // few px of leading padding, plus float noise between the JS-reported scroll metrics
            // and our derived progression). Without the slack, a heading-at-top landing produces
            // `|cur - bm| ≈ viewportFraction/2 + small`, which a strict viewport-only window
            // silently rounds OFF — exactly the bug the user reproduced.
            viewportFraction != null -> (viewportFraction / 2.0 + BOOKMARK_PAGE_EPS)
            else -> BOOKMARK_VIEWPORT_EPS
        }
        // `<= eps` (not `< eps`) so the boundary case is inclusive — geometrically, when the
        // bookmark anchor sits exactly at the viewport's top edge, |cur - bm| equals
        // `viewportFraction / 2` to machine precision, and a strict `<` would silently call that
        // OFF even though the anchor is visible. The same fix benefits Readium modes: a column-
        // or page-aligned bookmark whose progression matches the current locator to within
        // floating-point noise should also light.
        val matched = positions.any { bm ->
            bm.chapterHref == hrefNorm &&
                (prog == null || kotlin.math.abs(bm.progression - prog) <= eps)
        }
        matched
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var observeJob: Job? = null
    private var locatorJob: Job? = null
    private var orientationJob: Job? = null
    private var viewportFractionJob: Job? = null

    /**
     * Bind to a specific book. Cancels any previous observation and starts fresh.
     *
     * [currentLocator] — the live VM locator so [isCurrentPageBookmarked] stays reactive.
     * [currentOrientation] — selects the match-window strategy (see [isCurrentPageBookmarked]).
     * [continuousViewportFraction] — viewport size as a fraction of the chapter slot's pixel
     *   height; emits `null` until the user first scrolls, and `null` in non-continuous modes.
     *   The match-window in continuous is derived from this fraction.
     */
    fun bind(
        serverId: String,
        itemId: String,
        currentLocator: StateFlow<Locator?>,
        // Defaults to a Horizontal-only flow so existing call sites (and tests) that don't care
        // about the continuous-mode widened eps still compile; the production VM passes a real
        // flow tracking the user's chosen orientation.
        currentOrientation: StateFlow<ReaderOrientation> = MutableStateFlow(ReaderOrientation.Horizontal),
        continuousViewportFraction: StateFlow<Float?> = MutableStateFlow(null),
    ) {
        observeJob?.cancel()
        locatorJob?.cancel()
        orientationJob?.cancel()
        viewportFractionJob?.cancel()
        _bookmarkPositions.value = emptyList()
        _currentLocator.value = null
        _currentOrientation.value = currentOrientation.value
        _continuousViewportFraction.value = continuousViewportFraction.value

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
        viewportFractionJob = scope.launch {
            continuousViewportFraction.collect { fraction ->
                _continuousViewportFraction.value = fraction
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

        // Conservative fallback when the live continuous-mode viewport fraction hasn't been
        // emitted yet (cold open, no scroll events). 25% is wide enough to catch typical viewports
        // (~20–40% of chapter height) without being so wide it lights bookmarks halfway across
        // a chapter. The match window is replaced by `viewportFraction / 2` as soon as the
        // ContinuousReaderView produces its first onViewportFraction emission.
        internal const val BOOKMARK_VIEWPORT_EPS = 0.25
    }
}
