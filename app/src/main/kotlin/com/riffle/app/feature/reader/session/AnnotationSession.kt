package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.HighlightColor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Banner that communicates annotation-sync status to the reader chrome.
 *
 * Derived from [AnnotationSyncStatusStore.lastCycleOutcome]; the session maps domain outcomes to
 * these UI tokens so the screen stays decoupled from the sync internals.
 */
sealed class AnnotationSyncBanner {
    /** Last cycle succeeded. */
    object Synced : AnnotationSyncBanner()

    /** Last cycle failed (network, auth, etc.). */
    data class Failed(val message: String?) : AnnotationSyncBanner()
}

/**
 * Owns all annotation UI state and sync lifecycle for one open book.
 *
 * Lifted from [EpubReaderViewModel] as part of the VM split (#303). Operations that require
 * [org.readium.r2.shared.publication.Publication] — CFI building ([createHighlight], [toggleBookmark],
 * highlight render reconstruction, CFI→Locator resolution) — stay in the VM and are injected here
 * as resolver lambdas at [bind] time. This is the "payload resolver" seam described in the plan.
 *
 * **Why resolvers over lifting Publication into the session:**
 * [Publication] is a Readium type; the global constraint forbids `org.readium.*` imports inside
 * orchestrators (they live behind the [ReaderPresenter] seam). Lambdas let the VM keep ownership
 * of those operations while AnnotationSession stays the sole owner of annotation _state_.
 *
 * MUST NOT import android.webkit.* or ContinuousReaderView.
 */
class AnnotationSession @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val annotationStore: AnnotationStore,
    private val annotationStatusStore: AnnotationSyncStatusStore,
    private val progressFlushScope: ProgressFlushScope,
    /** Called on [bind] after [syncOnOpen]; returns the [Job] backing the live-pull loop. */
    @Assisted private val startLiveSync: (serverId: String, namespace: String, itemId: String) -> Job,
    /** Called on each annotation mutation to schedule a debounced push. */
    @Assisted private val scheduleSync: (serverId: String, namespace: String, itemId: String) -> Unit,
    /** Called on [bind] before [startLiveSync]. Suspend, so annotated with "open". */
    @Assisted("open") private val syncOnOpen: suspend (serverId: String, namespace: String, itemId: String) -> Unit,
    /** Called on [onBookClosed] via [ProgressFlushScope] to push pending annotations. */
    @Assisted("close") private val syncOnClose: suspend (serverId: String, namespace: String, itemId: String) -> Unit,
) {

    @AssistedFactory
    interface Factory {
        fun create(
            scope: CoroutineScope,
            startLiveSync: (String, String, String) -> Job,
            scheduleSync: (String, String, String) -> Unit,
            @Assisted("open") syncOnOpen: suspend (String, String, String) -> Unit,
            @Assisted("close") syncOnClose: suspend (String, String, String) -> Unit,
        ): AnnotationSession
    }

    // ---- State -------------------------------------------------------------------------------

    /** True once annotations are available for the active book (ABS side only). */
    private val _annotationsAvailable = MutableStateFlow(false)
    val annotationsAvailable: StateFlow<Boolean> = _annotationsAvailable

    /** Highlight locators + colour tokens rendered onto the EPUB page. */
    private val _highlightRenders = MutableStateFlow<List<EpubReaderViewModel.HighlightRender>>(emptyList())
    val highlightRenders: StateFlow<List<EpubReaderViewModel.HighlightRender>> = _highlightRenders

    /**
     * The highlight whose action popup is currently open (just created or tapped).
     * Null means no popup is showing.
     */
    private val _highlightToEdit = MutableStateFlow<EpubReaderViewModel.HighlightEditTarget?>(null)
    val highlightToEdit: StateFlow<EpubReaderViewModel.HighlightEditTarget?> = _highlightToEdit

    /** True while the annotations panel sheet is open. */
    private val _annotationsPanelVisible = MutableStateFlow(false)
    val annotationsPanelVisible: StateFlow<Boolean> = _annotationsPanelVisible

    /** All live annotations (highlights + bookmarks) for the current book, sorted by position. */
    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations

    /**
     * CONFLATED: [navigateToAnnotation] closes the panel before sending, so a second navigation
     * tap cannot occur before the first is consumed. Switch to BUFFERED if that invariant changes.
     */
    private val _annotationNavigationChannel = Channel<Locator>(Channel.CONFLATED)
    val annotationNavigationEvents: Flow<Locator> = _annotationNavigationChannel.receiveAsFlow()

    /**
     * Reflects the last annotation sync outcome as a UI banner. Null = no cycle has run yet
     * (initial state; nothing to show). Derived from [AnnotationSyncStatusStore].
     */
    val syncBanner: StateFlow<AnnotationSyncBanner?> = annotationStatusStore.lastCycleOutcome
        .map { outcome ->
            when (outcome) {
                is CycleOutcome.NeverRun -> null
                is CycleOutcome.Success -> AnnotationSyncBanner.Synced
                is CycleOutcome.Failed -> AnnotationSyncBanner.Failed(
                    when (outcome) {
                        is CycleOutcome.Failed.Network -> outcome.message
                        is CycleOutcome.Failed.Auth -> "Authentication failed (${outcome.code})"
                        is CycleOutcome.Failed.Tls -> outcome.message
                        is CycleOutcome.Failed.Server -> "Server error (${outcome.code})"
                        is CycleOutcome.Failed.Unknown -> outcome.message
                    }
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // ---- Bind-time state ---------------------------------------------------------------------

    /** Active book identity, set by [bind]. Null between books. */
    private var boundServerId: String? = null
    private var boundNamespace: String? = null
    private var boundItemId: String? = null

    /** Resolver from CFI string to [Locator]; provided at [bind] by the VM. Needs publication. */
    private var cfiLocatorResolverFn: (suspend (String) -> Locator?)? = null

    /** The live-pull job for the current book. Cancelled on [bind] / [onBookClosed]. */
    private var annotationLiveSyncJob: Job? = null

    /** Coroutine jobs for observing highlights and all-annotations. Cancelled on [bind]. */
    private var highlightObserveJob: Job? = null
    private var annotationsObserveJob: Job? = null

    // ---- Public API --------------------------------------------------------------------------

    /**
     * Bind to a new book. Cancels any previous observation jobs and sync loop, then starts
     * fresh observation + sync for the new book.
     *
     * @param highlightRenderResolver Converts a persisted [Annotation] to a [HighlightRender]
     *   (requires [Publication] to resolve CFI→progression and chapter HTML). Provided by VM.
     * @param cfiLocatorResolver Resolves a raw `epubcfi(...)` string to a [Locator]. Provided
     *   by VM. Used by [navigateToAnnotation] to emit the navigation target.
     */
    fun bind(
        serverId: String,
        namespace: String,
        itemId: String,
        currentLocator: StateFlow<Locator?>,
        highlightRenderResolver: suspend (Annotation) -> EpubReaderViewModel.HighlightRender?,
        cfiLocatorResolver: suspend (String) -> Locator?,
    ) {
        // Cancel previous book's jobs before starting new ones (single-flight guarantee).
        annotationLiveSyncJob?.cancel()
        annotationLiveSyncJob = null
        highlightObserveJob?.cancel()
        annotationsObserveJob?.cancel()
        _highlightRenders.value = emptyList()
        _annotations.value = emptyList()
        _annotationsPanelVisible.value = false
        _highlightToEdit.value = null
        _annotationsAvailable.value = false

        boundServerId = serverId
        boundNamespace = namespace
        boundItemId = itemId
        cfiLocatorResolverFn = cfiLocatorResolver

        // Mark annotations as available now that we have an ABS server id.
        _annotationsAvailable.value = true

        // Observe highlights → reconstruct HighlightRenders reactively.
        highlightObserveJob = scope.launch {
            annotationStore.observeHighlights(serverId, itemId).collect { annotations ->
                _highlightRenders.value = annotations.mapNotNull { highlightRenderResolver(it) }
            }
        }

        // Observe all annotations (highlights + bookmarks) for the panel.
        annotationsObserveJob = scope.launch {
            annotationStore.observeAnnotations(serverId, itemId).collect { list ->
                _annotations.value = list
            }
        }

        // Sync on open: pull peer annotations, then start the live-pull loop.
        scope.launch {
            syncOnOpen(serverId, namespace, itemId)
            annotationLiveSyncJob = startLiveSync(serverId, namespace, itemId)
        }
    }

    // ---- Highlight actions -------------------------------------------------------------------

    /** Open the highlight actions popup for [id] at [anchorRect]. */
    fun openHighlightActions(id: String, anchorRect: androidx.compose.ui.unit.IntRect) {
        _highlightToEdit.value = EpubReaderViewModel.HighlightEditTarget(id, anchorRect)
    }

    /** Open the note-read popup (no colour pickers / delete) for [id]. */
    fun openNoteReader(id: String, anchorRect: androidx.compose.ui.unit.IntRect) {
        _highlightToEdit.value = EpubReaderViewModel.HighlightEditTarget(id, anchorRect, noteOnly = true)
    }

    /** Dismiss the highlight actions popup. */
    fun dismissHighlightActions() {
        _highlightToEdit.value = null
    }

    /**
     * Emit a locator directly to the annotation navigation channel. Used by the VM's
     * openBook() path to snap to the initial annotation-nav target (openAtCfi) using the same
     * channel that [navigateToAnnotation] uses — so the screen only needs one subscriber.
     */
    fun emitAnnotationNavigation(locator: Locator) {
        _annotationNavigationChannel.trySend(locator)
    }

    /** Recolour an existing highlight; [annotationStore] re-emits → [highlightRenders] re-renders. */
    suspend fun recolorHighlight(id: String, color: HighlightColor) {
        annotationStore.recolor(id, color.token)
        scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
    }

    /** Soft-delete a highlight; [annotationStore] re-emits without it → decoration removed. */
    suspend fun deleteHighlight(id: String) {
        annotationStore.delete(id)
        scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
        if (_highlightToEdit.value?.id == id) _highlightToEdit.value = null
    }

    /** Save (or clear) the note on a highlight. Blank text is treated as null. */
    suspend fun updateHighlightNote(id: String, note: String?) {
        annotationStore.updateNote(id, note?.takeIf { it.isNotBlank() })
        scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
    }

    // ---- Annotations panel ------------------------------------------------------------------

    /** Open the annotations panel sheet. */
    fun openAnnotationsPanel() {
        _annotationsPanelVisible.value = true
    }

    /** Close the annotations panel sheet. */
    fun closeAnnotationsPanel() {
        _annotationsPanelVisible.value = false
    }

    /**
     * Navigate the reader to the annotation with [id], then close the panel.
     * Uses [cfiLocatorResolver] injected at [bind] to resolve the CFI → Locator.
     */
    fun navigateToAnnotation(id: String) {
        scope.launch {
            val annotation = _annotations.value.firstOrNull { it.id == id } ?: return@launch
            val resolver = cfiLocatorResolverFn ?: return@launch
            val locator = resolver(annotation.cfi) ?: return@launch
            _annotationNavigationChannel.trySend(locator)
            _annotationsPanelVisible.value = false
        }
    }

    /** Soft-delete any annotation (highlight or bookmark); clears highlight-edit state if needed. */
    suspend fun deleteAnnotation(id: String) {
        annotationStore.delete(id)
        scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
        if (_highlightToEdit.value?.id == id) _highlightToEdit.value = null
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    /**
     * Called when the reader is resumed from background. Re-arms the live-pull loop so peer
     * annotations remain fresh throughout a foreground session. The open-time [syncOnOpen] is
     * NOT repeated — only the periodic live-pull is restarted.
     *
     * No-op if [bind] has not been called for a synced book (no [boundServerId]).
     */
    fun onReaderResumed() {
        val sid = boundServerId ?: return
        val ns = boundNamespace ?: return
        val iid = boundItemId ?: return
        if (ns.isBlank()) return  // namespace not resolved — sync not configured this session
        annotationLiveSyncJob?.cancel()
        annotationLiveSyncJob = startLiveSync(sid, ns, iid)
    }

    /**
     * Called when the reader is closed (VM cleared). Cancels the live-sync loop and pushes
     * any pending annotations on [ProgressFlushScope] so the write survives viewModelScope
     * cancellation at teardown.
     */
    fun onBookClosed() {
        annotationLiveSyncJob?.cancel()
        annotationLiveSyncJob = null
        val sid = boundServerId ?: return
        val ns = boundNamespace ?: return
        val iid = boundItemId ?: return
        progressFlushScope.flush { syncOnClose(sid, ns, iid) }
    }
}
