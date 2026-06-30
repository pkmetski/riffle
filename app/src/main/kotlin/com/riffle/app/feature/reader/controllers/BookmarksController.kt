package com.riffle.app.feature.reader.controllers

import com.riffle.core.domain.normalizeEpubHref
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
     * Bookmark `progression` is whatever the locator emitted at save-time:
     *  - **Readium (paginated + vertical):** content-top progression. Bookmark anchor and
     *    current locator share the same frame, so a tight ±5% window is the correct match.
     *  - **Continuous:** viewport-midpoint progression (`ContinuousPositionTracker.locatorAt`).
     *    The bookmark anchor is visible iff `|midpoint_now - M_save| <= viewportFraction/2`.
     *    Without a live viewport-fraction signal, we use a fixed [BOOKMARK_VIEWPORT_EPS] (33%)
     *    which covers typical viewports plus short chapters where `vf/2 ≈ 0.30`.
     *
     * The `<= eps` (not `< eps`) makes the boundary case inclusive — when the bookmark anchor
     * sits exactly at the viewport's top edge, a strict `<` would silently call that OFF even
     * though the anchor is visible.
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(
        _bookmarkPositions,
        _currentLocator,
    ) { positions, locator ->
        val href = locator?.href?.toString() ?: return@combine false
        val prog = locator.locations.progression
        val hrefNorm = normalizeEpubHref(href)
        val eps = if (currentOrientation == ReaderOrientation.Continuous) BOOKMARK_VIEWPORT_EPS
        else BOOKMARK_PAGE_EPS
        positions.any { bm ->
            bm.chapterHref == hrefNorm &&
                (prog == null || kotlin.math.abs(bm.progression - prog) <= eps)
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var observeJob: Job? = null
    private var locatorJob: Job? = null

    /**
     * Bind to a specific book. Cancels any previous observation and starts fresh.
     *
     * [currentLocator] — the live VM locator so [isCurrentPageBookmarked] stays reactive.
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

    companion object {
        // ±5% within-chapter progression window for paginated / vertical mode — both emit
        // content-top progression and the bookmark stores the same, so a tight match is correct.
        internal const val BOOKMARK_PAGE_EPS = 0.05

        // ±33% within-chapter window for continuous mode. The locator emits the viewport-midpoint
        // progression while the bookmark anchor can sit anywhere from the viewport top to bottom,
        // so the geometric minimum is `viewportFraction / 2`. We use a static 33% as a
        // conservative cover: typical viewports (~20–40% of chapter height) put `vf/2` at
        // 0.10–0.20, while short chapters (≤ 2× viewport tall) push `vf/2` up to ~0.30; 33%
        // catches both with a small slack for anchor offsets and float noise.
        //
        // The trade-off: two bookmarks within ~33% of each other in the same chapter both light.
        // Acceptable for a boolean indicator. We previously tried plumbing the live viewport
        // fraction through to size this exactly, but the on-scroll emission churn flaked the
        // continuous→paginated annotation-repaint harness test (Compose recomposition pressure
        // prevented the chrome-reveal LaunchedEffect from idling within the test's waitUntil).
        // The static value here picks 33% as the band that covers all observed cases.
        internal const val BOOKMARK_VIEWPORT_EPS = 0.33
    }
}
