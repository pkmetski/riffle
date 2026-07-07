package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.HighlightColorPreferencesStore
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
    private val highlightColorPreferencesStore: HighlightColorPreferencesStore,
    private val progressFlushScope: ProgressFlushScope,
    /** Called on [bind] after [syncOnOpen]; returns the [Job] backing the live-pull loop. */
    @Assisted private val startLiveSync: (sourceId: String, namespace: String, itemId: String) -> Job,
    /** Called on each annotation mutation to schedule a debounced push. */
    @Assisted private val scheduleSync: (sourceId: String, namespace: String, itemId: String) -> Unit,
    /** Called on [bind] before [startLiveSync]. Suspend, so annotated with "open". */
    @Assisted("open") private val syncOnOpen: suspend (sourceId: String, namespace: String, itemId: String) -> Unit,
    /** Called on [onBookClosed] via [ProgressFlushScope] to push pending annotations. */
    @Assisted("close") private val syncOnClose: suspend (sourceId: String, namespace: String, itemId: String) -> Unit,
    /**
     * Called after a recolour or note-clear on a highlight that might now be merge-eligible with a
     * same-chapter neighbour. Publication-dependent (needs to rebuild the CFI range), so the VM
     * owns the implementation. Params: annotation id + the post-mutation colour + post-mutation note.
     * See docs/superpowers/specs/2026-07-05-highlight-auto-merge-design.md.
     */
    @Assisted("merge") private val mergeAfterEdit: suspend (id: String, color: String, note: String?) -> Unit,
) {

    @AssistedFactory
    interface Factory {
        fun create(
            scope: CoroutineScope,
            startLiveSync: (String, String, String) -> Job,
            scheduleSync: (String, String, String) -> Unit,
            @Assisted("open") syncOnOpen: suspend (String, String, String) -> Unit,
            @Assisted("close") syncOnClose: suspend (String, String, String) -> Unit,
            @Assisted("merge") mergeAfterEdit: suspend (String, String, String?) -> Unit,
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
     * Carries both the resolved locator and a flag for whether the annotation was a page-level
     * bookmark vs a text-anchored highlight/note. Continuous-mode landing now goes to the viewport
     * midpoint for BOTH types so the page-bookmark ribbon's stored midpoint progression matches
     * the reader's scrollY on arrival — landing bookmarks at the viewport top produced a full-
     * viewport offset between the lit ribbon position and the actual scroll landing on any book
     * with images or non-uniform text density. The flag is preserved on the event because
     * downstream (analytics, tests) may still branch on annotation type; the screen no longer
     * uses it to pick alignment.
     */
    /**
     * A "navigate to this annotation" event. [locator] resolves to (chapter, progression,
     * fragment) — the fragment is the range's START paragraph id (see [extractAnchorFromCfi]),
     * which is enough for Readium's own navigators (paginated + vertical scroll) to land on
     * the paragraph containing the highlight.
     *
     * Continuous mode uses [annotationId] to look up the actual `<mark data-riffle-ann="…">`
     * element in the DOM and centre the viewport on IT, not just on the enclosing paragraph.
     * Landing on the paragraph is close but visibly wrong when the highlight is mid- or
     * end-paragraph — the previous behaviour showed the paragraph's TOP at midpoint, so
     * highlights below the first line ended up unclear or fully off-screen. [annotationId] is
     * null only when the caller doesn't have one (e.g. server-progress or programmatic nav).
     */
    data class AnnotationNavigationEvent(
        val locator: Locator,
        val isBookmark: Boolean,
        val annotationId: String? = null,
    )

    /**
     * CONFLATED: [navigateToAnnotation] closes the panel before sending, so a second navigation
     * tap cannot occur before the first is consumed. Switch to BUFFERED if that invariant changes.
     */
    private val _annotationNavigationChannel = Channel<AnnotationNavigationEvent>(Channel.CONFLATED)
    val annotationNavigationEvents: Flow<AnnotationNavigationEvent> = _annotationNavigationChannel.receiveAsFlow()

    /**
     * The per-book "last-used" highlight colour. New highlights are born in this colour so the
     * user's most recent pick is remembered PER BOOK. Kept as a StateFlow so the VM can read
     * [StateFlow.value] synchronously at creation time; the initial value falls back to
     * [HighlightColor.DEFAULT] (the first entry in the palette) until [bind] wires up a book-
     * scoped source and until DataStore emits the first value for that book.
     */
    private val _lastUsedHighlightColor = MutableStateFlow(HighlightColor.DEFAULT)
    val lastUsedHighlightColor: StateFlow<HighlightColor> = _lastUsedHighlightColor

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
                        is CycleOutcome.Failed.Server -> "Source error (${outcome.code})"
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

    /** Observes the per-book last-used highlight colour. Cancelled on [bind]. */
    private var lastUsedColorObserveJob: Job? = null

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
        sourceId: String,
        namespace: String,
        itemId: String,
        highlightRenderResolver: suspend (Annotation) -> EpubReaderViewModel.HighlightRender?,
        cfiLocatorResolver: suspend (String) -> Locator?,
    ) {
        // Cancel previous book's jobs before starting new ones (single-flight guarantee).
        annotationLiveSyncJob?.cancel()
        annotationLiveSyncJob = null
        highlightObserveJob?.cancel()
        annotationsObserveJob?.cancel()
        lastUsedColorObserveJob?.cancel()
        // Reset to palette default so the previous book's colour doesn't leak into a new book that
        // has never had a colour picked. If the DataStore has a value for this book, the observer
        // below overwrites this on its first emission.
        _lastUsedHighlightColor.value = HighlightColor.DEFAULT
        _highlightRenders.value = emptyList()
        _annotations.value = emptyList()
        _annotationsPanelVisible.value = false
        _highlightToEdit.value = null
        _annotationsAvailable.value = false

        boundServerId = sourceId
        boundNamespace = namespace
        boundItemId = itemId
        cfiLocatorResolverFn = cfiLocatorResolver

        // Mark annotations as available now that we have an ABS server id.
        _annotationsAvailable.value = true

        // Observe highlights → reconstruct HighlightRenders reactively.
        highlightObserveJob = scope.launch {
            annotationStore.observeHighlights(sourceId, itemId).collect { annotations ->
                _highlightRenders.value = annotations.mapNotNull { highlightRenderResolver(it) }
            }
        }

        // Observe all annotations (highlights + bookmarks) for the panel.
        annotationsObserveJob = scope.launch {
            annotationStore.observeAnnotations(sourceId, itemId).collect { list ->
                _annotations.value = list
            }
        }

        // Observe the per-book last-used highlight colour so new highlights in THIS book are
        // born in whatever colour the user last picked here. Falls back to HighlightColor.DEFAULT
        // for books the user has never picked a colour on (see HighlightColorPreferencesStore).
        lastUsedColorObserveJob = scope.launch {
            highlightColorPreferencesStore.lastUsedColor(sourceId, itemId).collect {
                _lastUsedHighlightColor.value = it
            }
        }

        // Sync on open: pull peer annotations, then start the live-pull loop.
        scope.launch {
            syncOnOpen(sourceId, namespace, itemId)
            annotationLiveSyncJob = startLiveSync(sourceId, namespace, itemId)
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

    /**
     * The note-editor target — non-null while the note editor dialog is open. Kept in session
     * state (not Compose-local) so [dismissHighlightActions] can tell whether the actions popup
     * is closing to *transition into* the note editor (no merge yet) or as a true commit close.
     * See docs/superpowers/specs/2026-07-05-highlight-auto-merge-design.md.
     */
    private val _noteEditorTarget = MutableStateFlow<EpubReaderViewModel.HighlightEditTarget?>(null)
    val noteEditorTarget: StateFlow<EpubReaderViewModel.HighlightEditTarget?> = _noteEditorTarget

    /**
     * Transition from the highlight-actions popup to the note editor. Closes the actions popup
     * *without* firing the merge check — the commit point is deferred to [dismissNoteEditor], so
     * a merge cannot absorb the row before the note has been saved onto it.
     */
    fun openNoteEditor(id: String, anchorRect: androidx.compose.ui.unit.IntRect) {
        val target = _highlightToEdit.value?.takeIf { it.id == id }
            ?: EpubReaderViewModel.HighlightEditTarget(id, anchorRect)
        _noteEditorTarget.value = target
        _highlightToEdit.value = null
    }

    /**
     * Commit the note editor: persist [note] onto highlight [id], close the editor, then fire the
     * merge check with the row's post-commit state. Ordered so [mergeAfterEdit] sees the just-
     * written note — a race between "update note" and "close editor" would otherwise let a merge
     * absorb the row before the note blocks eligibility.
     */
    suspend fun commitNoteEdit(id: String, note: String?) {
        val normalized = note?.takeIf { it.isNotBlank() }
        annotationStore.updateNote(id, normalized)
        _noteEditorTarget.value = null
        val row = _annotations.value.firstOrNull { it.id == id }
        val color = row?.color ?: run {
            scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
            return
        }
        scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
        mergeAfterEdit(id, color, normalized)
    }

    /**
     * Cancel the note editor without changes. Still a commit point — the underlying row may have
     * been recoloured / had its note previously cleared inside the actions popup, and this dismiss
     * is the moment the user is done editing.
     */
    fun cancelNoteEdit() {
        val target = _noteEditorTarget.value
        _noteEditorTarget.value = null
        val id = target?.id ?: return
        val row = _annotations.value.firstOrNull { it.id == id } ?: return
        if (row.type != AnnotationEntity.TYPE_HIGHLIGHT) return
        scope.launch { mergeAfterEdit(id, row.color, row.note) }
    }

    /**
     * Dismiss the highlight actions popup. Popup close = commit — auto-merge with a same-colour
     * no-note neighbour if the row's final state is eligible. Individual recolours / note edits
     * inside the popup do NOT fire; the user is still iterating.
     *
     * If the note editor is currently open (user tapped "Add note"), this is a *transition*, not a
     * commit — [openNoteEditor] already cleared [highlightToEdit], and the merge check is deferred
     * to [dismissNoteEditor].
     */
    fun dismissHighlightActions() {
        val target = _highlightToEdit.value
        _highlightToEdit.value = null
        if (_noteEditorTarget.value != null) return
        val id = target?.id ?: return
        val row = _annotations.value.firstOrNull { it.id == id } ?: return
        if (row.type != AnnotationEntity.TYPE_HIGHLIGHT) return
        scope.launch { mergeAfterEdit(id, row.color, row.note) }
    }

    /**
     * Emit a locator directly to the annotation navigation channel. Used by the VM's
     * openBook() path to snap to the initial annotation-nav target (openAtCfi) using the same
     * channel that [navigateToAnnotation] uses — so the screen only needs one subscriber.
     * The openAtCfi path is highlight/note-shaped (text anchor), so we send `isBookmark = false`
     * to reflect the annotation type on the event. Continuous-mode landing is uniform midpoint
     * for both types; the flag no longer affects alignment.
     */
    fun emitAnnotationNavigation(locator: Locator) {
        _annotationNavigationChannel.trySend(AnnotationNavigationEvent(locator, isBookmark = false))
    }

    /**
     * Recolour an existing highlight; [annotationStore] re-emits → [highlightRenders] re-renders.
     * Also persists [color] as the last-used highlight colour FOR THE CURRENT BOOK so subsequent
     * new highlights in the same book are born in it. No-op on the last-used store if the session
     * isn't bound (should not happen from the reader UI).
     */
    suspend fun recolorHighlight(id: String, color: HighlightColor) {
        annotationStore.recolor(id, color.token)
        val sid = boundServerId ?: return
        val iid = boundItemId ?: return
        highlightColorPreferencesStore.setLastUsedColor(sid, iid, color)
        // Merge check is deferred to [dismissHighlightActions] — the popup close is the commit
        // point. Firing here would absorb a neighbour mid-iteration while the user is still
        // deciding on colour/note.
        scheduleSync(sid, boundNamespace ?: return, iid)
    }

    /** Soft-delete a highlight; [annotationStore] re-emits without it → decoration removed. */
    suspend fun deleteHighlight(id: String) {
        annotationStore.delete(id)
        scheduleSync(boundServerId ?: return, boundNamespace ?: return, boundItemId ?: return)
        if (_highlightToEdit.value?.id == id) _highlightToEdit.value = null
    }

    /** Save (or clear) the note on a highlight. Blank text is treated as null. */
    suspend fun updateHighlightNote(id: String, note: String?) {
        val normalized = note?.takeIf { it.isNotBlank() }
        annotationStore.updateNote(id, normalized)
        // Merge check is deferred to [dismissHighlightActions] — see recolorHighlight.
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
            // Annotation.type uses the database-layer constants from AnnotationEntity. A typo
            // matching a lowercased literal here silently flipped every annotation to
            // isBookmark=false, which inverted the continuous-mode landing.
            _annotationNavigationChannel.trySend(
                AnnotationNavigationEvent(
                    locator = locator,
                    isBookmark = annotation.type == AnnotationEntity.TYPE_BOOKMARK,
                    // Carry the id so continuous mode can look up the actual mark and centre on
                    // it (see AnnotationNavigationEvent doc). Paginated / vertical don't need it —
                    // they rely on Readium's own fragment-based landing.
                    annotationId = id,
                ),
            )
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
     * Cancel the live-sync polling loop when the reader is backgrounded. The complementary
     * [onReaderResumed] restarts it. Together they form the lifecycle gate the original VM
     * implemented as "STARTED gating" — preserves the same network/battery behaviour.
     *
     * No-op if [bind] has not been called for a synced book.
     */
    fun onReaderClosed() {
        annotationLiveSyncJob?.cancel()
        annotationLiveSyncJob = null
    }

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
