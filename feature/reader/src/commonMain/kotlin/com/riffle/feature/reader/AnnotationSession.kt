package com.riffle.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.HighlightColor
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.sync.AnnotationSyncStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns all annotation UI state and sync lifecycle for one open book.
 *
 * Lifted from [:app] `AnnotationSession` to `feature:reader` commonMain so iOS can share the
 * same session, reading from the three preference stores ([HighlightColorPreferencesStore],
 * [EmphasisPreferencesStore], [HighlightsResumeStore]) that were previously bound but never
 * consumed on iOS.
 *
 * Operations that require a platform Readium [Locator] type — CFI building, CFI→Locator
 * resolution, highlight render reconstruction — stay in the platform host and are injected here
 * as lambdas at [bind] time. [LocatorJson] (the Readium JSON-serialised locator string) is the
 * platform-neutral position currency.
 *
 * MUST NOT import android.webkit.*, ContinuousReaderView, or any Readium JVM / Swift type.
 */
class AnnotationSession constructor(
    private val scope: CoroutineScope,
    private val annotationStore: AnnotationStore,
    private val annotationStatusStore: AnnotationSyncStatusStore,
    private val highlightColorPreferencesStore: HighlightColorPreferencesStore,
    private val emphasisPreferencesStore: EmphasisPreferencesStore,
    private val progressFlushScope: ProgressFlushScope,
    private val startLiveSync: (sourceId: String, namespace: String, itemId: String) -> Job,
    private val scheduleSync: (sourceId: String, namespace: String, itemId: String) -> Unit,
    private val syncOnOpen: suspend (sourceId: String, namespace: String, itemId: String) -> Unit,
    private val syncOnClose: suspend (sourceId: String, namespace: String, itemId: String) -> Unit,
    private val mergeAfterEdit: suspend (id: String, color: String, note: String?) -> Unit,
    private val commitDraftWithNote: suspend (note: String?) -> Unit,
) {

    fun interface Factory {
        fun create(
            scope: CoroutineScope,
            startLiveSync: (String, String, String) -> Job,
            scheduleSync: (String, String, String) -> Unit,
            syncOnOpen: suspend (String, String, String) -> Unit,
            syncOnClose: suspend (String, String, String) -> Unit,
            mergeAfterEdit: suspend (String, String, String?) -> Unit,
            commitDraftWithNote: suspend (String?) -> Unit,
        ): AnnotationSession
    }

    // ---- State -------------------------------------------------------------------------------

    private val _annotationsAvailable = MutableStateFlow(false)
    val annotationsAvailable: StateFlow<Boolean> = _annotationsAvailable

    private val _highlightRenders = MutableStateFlow<List<HighlightRender>>(emptyList())
    val highlightRenders: StateFlow<List<HighlightRender>> = _highlightRenders

    private val _highlightToEdit = MutableStateFlow<HighlightEditTarget?>(null)
    val highlightToEdit: StateFlow<HighlightEditTarget?> = _highlightToEdit

    private val _annotationsPanelVisible = MutableStateFlow(false)
    val annotationsPanelVisible: StateFlow<Boolean> = _annotationsPanelVisible

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations

    private val _emphasisPool = MutableStateFlow<List<Annotation>>(emptyList())
    val emphasisPool: StateFlow<List<Annotation>> = _emphasisPool

    /**
     * A "navigate to this annotation" event. [locatorJson] is the platform-neutral Readium
     * locator JSON string resolved by the CFI resolver lambda supplied at [bind] time.
     *
     * The [annotation] is carried so the consuming host can enrich the locator before navigation
     * (e.g. Android restores the persisted TextQuote / element-anchor via
     * `Locator.withAnnotationNavigationAnchor`). iOS uses the locatorJson directly with its
     * Readium Swift navigator.
     */
    data class AnnotationNavigationEvent(
        val locatorJson: LocatorJson,
        val annotation: Annotation?,
        val isBookmark: Boolean,
        val annotationId: String? = null,
        val imageSrc: String? = null,
    )

    private val _annotationNavigationChannel = Channel<AnnotationNavigationEvent>(Channel.CONFLATED)
    val annotationNavigationEvents: Flow<AnnotationNavigationEvent> = _annotationNavigationChannel.receiveAsFlow()

    private val _lastUsedHighlightColor = MutableStateFlow(HighlightColor.DEFAULT)
    val lastUsedHighlightColor: StateFlow<HighlightColor> = _lastUsedHighlightColor

    private val _lastUsedColorIsNone = MutableStateFlow(false)
    val lastUsedColorIsNone: StateFlow<Boolean> = _lastUsedColorIsNone

    private val _draftAnnotation = MutableStateFlow<DraftAnnotation?>(null)
    val draftAnnotation: StateFlow<DraftAnnotation?> = _draftAnnotation

    data class DraftAnnotation(
        val sourceId: String,
        val itemId: String,
        val cfiRange: String,
        val textSnippet: String,
        val textBefore: String,
        val textAfter: String,
        val chapterHref: String,
        val spineIndex: Int,
        val progression: Double,
        val embeddedFigures: List<EmbeddedFigure>?,
        val originFontFamily: String,
        val textSnippetHtml: String?,
        val anchorRect: AnchorRect,
        val locatorJson: LocatorJson,
    )

    fun beginAnnotationDraft(draft: DraftAnnotation) {
        _draftAnnotation.value = draft
        _highlightToEdit.value = HighlightEditTarget(
            id = DRAFT_ANNOTATION_ID,
            anchorRect = draft.anchorRect,
        )
    }

    fun discardDraft() {
        _draftAnnotation.value = null
        _highlightToEdit.value = null
    }

    fun consumeDraft() {
        _draftAnnotation.value = null
    }

    fun forceCloseHighlightEditTarget() {
        _highlightToEdit.value = null
    }

    companion object {
        const val DRAFT_ANNOTATION_ID: String = "__draft__"
    }

    private val _lastUsedEmphasisStyles = MutableStateFlow<Set<EmphasisStyle>>(emptySet())
    val lastUsedEmphasisStyles: StateFlow<Set<EmphasisStyle>> = _lastUsedEmphasisStyles

    // ---- Bind-time state ---------------------------------------------------------------------

    private val syncCoordinator = AnnotationSyncCoordinator(
        scope = scope,
        progressFlushScope = progressFlushScope,
        startLiveSync = startLiveSync,
        scheduleSync = scheduleSync,
        syncOnOpen = syncOnOpen,
        syncOnClose = syncOnClose,
    )

    val syncBanner: StateFlow<AnnotationSyncBanner?> = syncCoordinator.syncBanner(annotationStatusStore)

    private var cfiLocatorResolverFn: (suspend (String) -> LocatorJson?)? = null

    private var highlightObserveJob: Job? = null
    private var lastUsedColorObserveJob: Job? = null
    private var lastUsedEmphasisObserveJob: Job? = null

    // ---- Public API --------------------------------------------------------------------------

    fun bind(
        sourceId: String,
        namespace: String,
        itemId: String,
        highlightRenderResolver: suspend (Annotation) -> List<HighlightRender>,
        cfiLocatorResolver: suspend (String) -> LocatorJson?,
    ) {
        syncCoordinator.bind(sourceId, namespace, itemId)
        highlightObserveJob?.cancel()
        lastUsedColorObserveJob?.cancel()
        lastUsedEmphasisObserveJob?.cancel()
        _lastUsedHighlightColor.value = HighlightColor.DEFAULT
        _highlightRenders.value = emptyList()
        _annotations.value = emptyList()
        _annotationsPanelVisible.value = false
        _highlightToEdit.value = null
        _annotationsAvailable.value = false

        cfiLocatorResolverFn = cfiLocatorResolver

        _annotationsAvailable.value = true

        highlightObserveJob = scope.launch {
            kotlinx.coroutines.flow.combine(
                annotationStore.observeAnnotations(sourceId, itemId),
                _highlightToEdit,
                _draftAnnotation,
            ) { all, target, draft -> Triple(all, target?.id, draft) }
                .collect { (all, editingId, draft) ->
                    _emphasisPool.value = all.filter {
                        it.type == AnnotationEntity.TYPE_EMPHASIS
                    }
                    _annotations.value = all.filter {
                        it.type != AnnotationEntity.TYPE_EMPHASIS
                    }
                    val highlights = all.filter {
                        it.type == AnnotationEntity.TYPE_HIGHLIGHT
                    }
                    val real = highlights.flatMap { h ->
                        highlightRenderResolver(h).map { r ->
                            r.copy(isBeingEdited = r.id == editingId)
                        }
                    }
                    val draftPreviewColor = if (_lastUsedColorIsNone.value) "" else _lastUsedHighlightColor.value.token
                    val draftRender = draft?.let { d ->
                        HighlightRender(
                            id = DRAFT_ANNOTATION_ID,
                            locatorJson = d.locatorJson,
                            color = draftPreviewColor,
                            note = null,
                            emphasisStyles = _lastUsedEmphasisStyles.value,
                            isBeingEdited = true,
                        )
                    }
                    _highlightRenders.value = real + listOfNotNull(draftRender)
                }
        }

        lastUsedColorObserveJob = scope.launch {
            highlightColorPreferencesStore.lastUsedColor(sourceId, itemId).collect {
                _lastUsedHighlightColor.value = it
            }
        }
        _lastUsedColorIsNone.value = false
        scope.launch {
            highlightColorPreferencesStore.lastUsedIsNone(sourceId, itemId).collect {
                _lastUsedColorIsNone.value = it
            }
        }

        _lastUsedEmphasisStyles.value = emptySet()
        lastUsedEmphasisObserveJob = scope.launch {
            emphasisPreferencesStore.lastUsedStyles(sourceId, itemId).collect {
                _lastUsedEmphasisStyles.value = it
            }
        }

        syncCoordinator.startSyncForBoundBook()
    }

    fun updateNamespace(namespace: String?) = syncCoordinator.updateNamespace(namespace)

    // ---- Highlight actions -------------------------------------------------------------------

    fun openHighlightActions(
        id: String,
        anchorRect: AnchorRect = AnchorRect.Zero,
        justCreatedFromDraft: Boolean = false,
    ) {
        _highlightToEdit.value = HighlightEditTarget(
            id = id,
            anchorRect = anchorRect,
            justCreatedFromDraft = justCreatedFromDraft,
        )
    }

    fun openNoteReader(id: String, anchorRect: AnchorRect = AnchorRect.Zero) {
        _highlightToEdit.value = HighlightEditTarget(id, anchorRect, noteOnly = true)
    }

    private val _noteEditorTarget = MutableStateFlow<HighlightEditTarget?>(null)
    val noteEditorTarget: StateFlow<HighlightEditTarget?> = _noteEditorTarget

    fun openNoteEditor(id: String, anchorRect: AnchorRect = AnchorRect.Zero) {
        val target = _highlightToEdit.value?.takeIf { it.id == id }
            ?: HighlightEditTarget(id, anchorRect)
        _noteEditorTarget.value = target
        _highlightToEdit.value = null
    }

    suspend fun commitNoteEdit(id: String, note: String?) {
        val normalized = note?.takeIf { it.isNotBlank() }
        if (id == DRAFT_ANNOTATION_ID) {
            _noteEditorTarget.value = null
            commitDraftWithNote(normalized)
            return
        }
        annotationStore.updateNote(id, normalized)
        _noteEditorTarget.value = null
        val row = _annotations.value.firstOrNull { it.id == id }
        val color = row?.color ?: run {
            syncCoordinator.scheduleSyncIfReady()
            return
        }
        syncCoordinator.scheduleSyncIfReady()
        mergeAfterEdit(id, color, normalized)
    }

    fun cancelNoteEdit() {
        val target = _noteEditorTarget.value
        _noteEditorTarget.value = null
        val id = target?.id ?: return
        if (id == DRAFT_ANNOTATION_ID) {
            scope.launch { commitDraftWithNote(null) }
            return
        }
        val row = _annotations.value.firstOrNull { it.id == id } ?: return
        if (row.type != AnnotationEntity.TYPE_HIGHLIGHT) return
        scope.launch { mergeAfterEdit(id, row.color, row.note) }
    }

    fun dismissHighlightActions() {
        val target = _highlightToEdit.value
        _highlightToEdit.value = null
        if (target?.id == DRAFT_ANNOTATION_ID) {
            _draftAnnotation.value = null
            return
        }
        if (_noteEditorTarget.value != null) return
        val nonNullTarget = target ?: return
        val id = nonNullTarget.id
        scope.launch {
            val row = awaitAnnotation(id) ?: return@launch
            if (nonNullTarget.justCreatedFromDraft &&
                row.type == AnnotationEntity.TYPE_HIGHLIGHT &&
                row.color.isBlank() &&
                row.note.isNullOrBlank() &&
                _emphasisPool.value.none { it.cfi == row.cfi }
            ) {
                annotationStore.delete(id)
                syncCoordinator.scheduleSyncIfReady()
                return@launch
            }
            if (row.type != AnnotationEntity.TYPE_HIGHLIGHT &&
                row.type != AnnotationEntity.TYPE_IMAGE
            ) return@launch
            mergeAfterEdit(id, row.color, row.note)
        }
    }

    internal suspend fun awaitAnnotation(id: String, timeoutMs: Long = 500): Annotation? =
        withTimeoutOrNull(timeoutMs) {
            _annotations.first { list -> list.any { it.id == id } }.first { it.id == id }
        }

    /**
     * Emit a navigation event for the given locator JSON and optional annotation id.
     *
     * The [annotation] carried in the event lets the consuming host enrich the locator (e.g.
     * Android restores the exact TextQuote / element anchor) before navigating.
     */
    fun emitAnnotationNavigation(locatorJson: LocatorJson, annotationId: String?, annotation: Annotation? = null) {
        _annotationNavigationChannel.trySend(
            AnnotationNavigationEvent(
                locatorJson = locatorJson,
                annotation = annotation,
                isBookmark = false,
                annotationId = annotationId,
            ),
        )
    }

    suspend fun persistLastUsedColorToken(colorToken: String) {
        val sid = syncCoordinator.sourceId ?: return
        val iid = syncCoordinator.itemId ?: return
        if (colorToken.isEmpty()) {
            _lastUsedColorIsNone.value = true
            highlightColorPreferencesStore.setLastUsedIsNone(sid, iid, true)
        } else {
            _lastUsedColorIsNone.value = false
            _lastUsedHighlightColor.value = HighlightColor.fromToken(colorToken)
            highlightColorPreferencesStore.setLastUsedColor(sid, iid, HighlightColor.fromToken(colorToken))
            highlightColorPreferencesStore.setLastUsedIsNone(sid, iid, false)
        }
    }

    suspend fun recolorHighlight(id: String, color: HighlightColor) {
        annotationStore.recolor(id, color.token)
        val sid = syncCoordinator.sourceId ?: return
        val iid = syncCoordinator.itemId ?: return
        highlightColorPreferencesStore.setLastUsedColor(sid, iid, color)
        highlightColorPreferencesStore.setLastUsedIsNone(sid, iid, false)
        syncCoordinator.scheduleSyncIfReady()
    }

    suspend fun recolorHighlightRaw(id: String, colorToken: String) {
        annotationStore.recolor(id, colorToken)
        val sid = syncCoordinator.sourceId ?: return
        val iid = syncCoordinator.itemId ?: return
        if (colorToken.isEmpty()) {
            highlightColorPreferencesStore.setLastUsedIsNone(sid, iid, true)
        }
        syncCoordinator.scheduleSyncIfReady()
    }

    suspend fun deleteHighlight(id: String) {
        annotationStore.delete(id)
        syncCoordinator.scheduleSyncIfReady()
        if (_highlightToEdit.value?.id == id) _highlightToEdit.value = null
    }

    suspend fun updateHighlightNote(id: String, note: String?) {
        val normalized = note?.takeIf { it.isNotBlank() }
        annotationStore.updateNote(id, normalized)
        syncCoordinator.scheduleSyncIfReady()
    }

    // ---- Annotations panel ------------------------------------------------------------------

    fun openAnnotationsPanel() {
        _annotationsPanelVisible.value = true
    }

    fun closeAnnotationsPanel() {
        _annotationsPanelVisible.value = false
    }

    fun navigateToAnnotation(id: String) {
        scope.launch {
            val annotation = _annotations.value.firstOrNull { it.id == id } ?: return@launch
            val resolver = cfiLocatorResolverFn ?: return@launch
            val resolvedLocatorJson = resolver(annotation.cfi) ?: return@launch
            val isBookmark = annotation.type == AnnotationEntity.TYPE_BOOKMARK
            val finalLocatorJson = if (isBookmark) {
                resolvedLocatorJson.withBookmarkNavigationAnchor(annotation.fragmentAnchor, annotation.progression)
            } else {
                resolvedLocatorJson
            }
            _annotationNavigationChannel.trySend(
                AnnotationNavigationEvent(
                    locatorJson = finalLocatorJson,
                    annotation = annotation,
                    isBookmark = isBookmark,
                    annotationId = id,
                    imageSrc = if (annotation.type == AnnotationEntity.TYPE_IMAGE) {
                        annotation.imageHref ?: finalLocatorJson.fragmentsFromLocatorJson()?.firstOrNull()?.let { "#$it" }
                    } else null,
                ),
            )
            _annotationsPanelVisible.value = false
        }
    }

    suspend fun deleteAnnotation(id: String) {
        val target = _annotations.value.firstOrNull { it.id == id }
        if (target != null) {
            _emphasisPool.value
                .filter { it.cfi == target.cfi }
                .forEach { annotationStore.delete(it.id) }
        }
        annotationStore.delete(id)
        syncCoordinator.scheduleSyncIfReady()
        if (_highlightToEdit.value?.id == id) _highlightToEdit.value = null
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    fun onReaderClosed() = syncCoordinator.onReaderClosed()
    fun onReaderResumed() = syncCoordinator.onReaderResumed()
    fun onBookClosed() = syncCoordinator.onBookClosed()
}

/**
 * Adjusts a CFI-resolved locator JSON for bookmark navigation:
 * - New-style bookmarks (have [fragmentAnchor]): inject fragmentAnchor as `locations.fragments`
 *   and override `locations.progression` with the stored bookmark progression. The stored
 *   progression is the JS-measured scroll fraction at save-time — more accurate than the CFI
 *   approximation and preserves 0.0 (first column) correctly.
 * - Legacy bookmarks (no [fragmentAnchor]): clear `locations.fragments` so the snap logic
 *   falls back to the progression value instead of always snapping to the container's column 0.
 *
 * Uses simple string substitution rather than a JSON library so it remains usable in commonMain
 * without pulling in kotlinx.serialization on the feature:reader classpath.
 */
internal fun LocatorJson.withBookmarkNavigationAnchor(fragmentAnchor: String?, storedProgression: Double): LocatorJson {
    val locationsIdx = indexOf("\"locations\"")
    if (locationsIdx < 0) return this
    val objStart = indexOf('{', locationsIdx)
    val objEnd = indexOf('}', objStart)
    if (objStart < 0 || objEnd < 0) return this
    val locationsJson = substring(objStart + 1, objEnd)

    val newFragments = if (fragmentAnchor != null) "\"$fragmentAnchor\"" else null
    val fragmentsReplacement = if (newFragments != null) "\"fragments\":[$newFragments]" else "\"fragments\":[]"

    val withFragments = if (locationsJson.contains("\"fragments\"")) {
        val fragStart = locationsJson.indexOf("\"fragments\"")
        val arrStart = locationsJson.indexOf('[', fragStart)
        val arrEnd = locationsJson.indexOf(']', arrStart)
        locationsJson.substring(0, fragStart) + fragmentsReplacement + locationsJson.substring(arrEnd + 1)
    } else if (fragmentAnchor != null) {
        "$locationsJson,$fragmentsReplacement"
    } else {
        locationsJson
    }

    val withProgression = if (fragmentAnchor != null) {
        // Override CFI-derived progression with the stored bookmark value (preserves 0.0).
        val progressionPattern = Regex("\"progression\"\\s*:\\s*[0-9.eE+\\-]+")
        progressionPattern.replace(withFragments, "\"progression\":$storedProgression")
            .let { if (!it.contains("\"progression\"")) "$it,\"progression\":$storedProgression" else it }
    } else {
        withFragments
    }

    return substring(0, objStart + 1) + withProgression + substring(objEnd)
}

/**
 * Extracts the `locations.fragments` list from a Readium locator JSON string.
 *
 * Used by [AnnotationSession.navigateToAnnotation] to derive the `#fragment-id` image src for
 * TYPE_IMAGE annotations that pre-date the `imageHref` column (migration 46→47). The field
 * path in Readium's canonical JSON is `locations.fragments`.
 */
private fun LocatorJson.fragmentsFromLocatorJson(): List<String>? = runCatching {
    // Minimal JSON parsing without a dependency on kotlinx.serialization from this file.
    // Readium serialises fragments as: "locations":{"fragments":["id1","id2"]}
    val locationsStart = indexOf("\"locations\"")
    if (locationsStart < 0) return null
    val fragmentsStart = indexOf("\"fragments\"", locationsStart)
    if (fragmentsStart < 0) return null
    val arrayStart = indexOf('[', fragmentsStart)
    val arrayEnd = indexOf(']', arrayStart)
    if (arrayStart < 0 || arrayEnd < 0) return null
    val inner = substring(arrayStart + 1, arrayEnd).trim()
    if (inner.isEmpty()) return emptyList()
    inner.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
}.getOrNull()
