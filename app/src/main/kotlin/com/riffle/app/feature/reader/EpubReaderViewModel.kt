@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.epubCfiToSpineIndex
import com.riffle.core.domain.normalizeEpubHref
import android.app.Application
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.audiobook.AudiobookHandoffState
import com.riffle.app.feature.reader.controllers.VolumeKeyDispatcher
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudController
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerSyncOutcome
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.Clock
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.cfiDocPathToProgression
import com.riffle.core.domain.extractAnchorFromCfi
import com.riffle.core.domain.extractCfiDocPath
import com.riffle.core.domain.progressionToCfiDocPath
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.app.feature.reader.session.AnnotationSession
import com.riffle.app.feature.reader.session.FormattingSession
import com.riffle.app.feature.reader.session.PositionOrchestrator
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionCoordinator
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.HighlightsResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TimeProvider
import com.riffle.core.domain.TimeRemaining
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.TocRepository
import com.riffle.core.domain.resolveEpubHref
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.app.feature.reader.highlights.ChapterElision
import com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory
import com.riffle.app.feature.reader.highlights.ReaderSource
import com.riffle.app.feature.reader.highlights.toFormattingScope
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

// The audiobook follows the live audio on a tighter cadence than the 30s ebook reconcile, so a
// listen reaches the server within seconds rather than only on the next ebook tick.
// Debounce window for persisting a playback-speed change, so a granular scrub/slide settles to a
// single write rather than one per intermediate 0.05× value.
private const val SPEED_SAVE_DEBOUNCE_MS = 400L

/**
 * Placeholder `font-family` value written on the entity when a live WebView probe returned
 * nothing at annotation-create time (rare selection-teardown race, or bookmarks toggled without
 * a prior selection). Deliberately plain "serif" so the elided view still declares a
 * font-family — [HighlightsPublicationFactory]'s sanitizer will let this pass through while
 * anything the DB coughs up beyond the safe allowlist is dropped. Issue #484.
 */
private const val FALLBACK_ORIGIN_FONT_FAMILY = "serif"

/** Convenience for the merge-inherit path: returns [Annotation.originFontFamily] on the
 *  domain projection, or null when the annotation predates issue #484 and hasn't been touched
 *  by lazy backfill yet. Callers fall back to [FALLBACK_ORIGIN_FONT_FAMILY]. */
private fun entityFontFamily(annotation: Annotation): String? =
    annotation.originFontFamily?.takeIf { it.isNotBlank() }

/** Plurality (mode) `originFontFamily` across every annotation in [chapters], skipping blanks.
 *  Returns null when nothing has a value yet — callers use that to mean "emit no `font-family`
 *  at all" (falls through to ReadiumCSS default). Ties broken by first-seen order. Issue #484. */
private fun pluralityOriginFont(chapters: List<ChapterElision>): String? =
    chapters.asSequence()
        .flatMap { it.highlights.asSequence() }
        .mapNotNull { it.originFontFamily?.takeIf { f -> f.isNotBlank() } }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
// Characters of textAfter used to build each highlight's context window for position
// disambiguation in the overlap-detection logic (see createHighlight).
private const val OVERLAP_CONTEXT_LEN = 60

/**
 * Whether Reading-Session tracking, ABS progress-sync PATCHes, and highlight/bookmark creation
 * gestures should run for this reader instance (ADR 0041, Task 8). Highlights mode displays a
 * synthesised, elided Publication built from stored highlights — it is not "reading" the real
 * book, so none of these side effects should fire against the ABS item or the annotation store.
 */
internal fun shouldRunReadingSideEffects(source: ReaderSource): Boolean =
    source != ReaderSource.Highlights

sealed class ReaderState {
    data object Loading : ReaderState()
    data class Ready(
        val publication: Publication,
        val title: String,
        val initialLocator: Locator?,
        /** The annotation id when this open was triggered by an annotation tap (openAtCfi flow).
         *  Continuous mode uses it to anchor the initial scroll against the DOM mark for the
         *  annotation — the precise post-reflow Y instead of char-fraction × measured-height. */
        val initialFocusAnnotationId: String? = null,
    ) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

private const val PREPARING_MESSAGE = "Preparing narration…"
private const val PREPARING_SLOW_TIMEOUT_MS = 15_000L

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryObserver: LibraryObserver,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val epubRepository: EpubRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val readingSessionRepository: ReadingSessionRepository,
    private val timeProvider: TimeProvider,
    private val readerStateHolder: ReaderStateHolder,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    private val streamingSessionFactory: com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory,
    private val playerCoordinator: PlayerCoordinator,
    private val storytellerSyncController: StorytellerPositionSyncController,
    private val sourceRepository: SourceRepository,
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val connectivityObserver: ConnectivityObserver,
    private val readerSyncFactory: ReaderSyncFactory,
    private val readingPositionStore: ReadingPositionStore,
    // Sync-store views of the same position rows for the matched dual-write (ADR 0030): read this
    // book's reading row stamps and mirror the translated audiobook second onto the sibling row.
    private val readingSyncStore: com.riffle.core.domain.SyncPositionStore<String>,
    private val audioSyncStore: com.riffle.core.domain.SyncPositionStore<Double>,
    // While this reader is open it drives the book's reconciliation; the durable sweep must skip it
    // so it can't absorb a cross-device server-win the visible reader hasn't jumped to (ADR 0030).
    private val openReconcileTargets: com.riffle.core.data.OpenReconcileTargets,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val annotationStore: AnnotationStore,
    private val annotationSyncController: com.riffle.core.data.AnnotationSyncController,
    private val nowPlayingStore: com.riffle.app.playback.NowPlayingStore,
    private val progressFlushScope: ProgressFlushScope,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val readingSpeedStore: ReadingSpeedStore,
    private val audiobookHandoffState: AudiobookHandoffState,
    private val sidecarStore: com.riffle.core.data.ReadaloudSidecarStore,
    private val formattingSessionFactory: FormattingSession.Factory,
    private val bookmarksControllerFactory: com.riffle.app.feature.reader.controllers.BookmarksController.Factory,
    private val searchControllerFactory: com.riffle.app.feature.reader.controllers.SearchController.Factory,
    private val wakeLockControllerFactory: com.riffle.app.feature.reader.controllers.WakeLockController.Factory,
    private val volumeKeyDispatcher: VolumeKeyDispatcher,
    private val cadenceController: com.riffle.app.feature.reader.cadence.CadenceController,
    private val positionOrchestratorFactory: PositionOrchestrator.Factory,
    private val annotationSessionFactory: com.riffle.app.feature.reader.session.AnnotationSession.Factory,
    private val readaloudSessionFactory: com.riffle.app.feature.reader.session.ReadaloudSession.Factory,
    private val readerSessionLifecycleFactory: com.riffle.app.feature.reader.session.ReaderSessionLifecycle.Factory,
    internal val logger: Logger,
    private val clock: Clock,
    val dispatchers: DispatcherProvider,
    private val highlightsPublicationFactory: HighlightsPublicationFactory,
    private val annotationDao: AnnotationDao,
    private val libraryItemDao: com.riffle.core.database.LibraryItemDao,
    private val highlightsResumeStore: HighlightsResumeStore,
    private val tocRepository: TocRepository,
    private val figuresInRangeResolver: FiguresInRangeResolver,
    private val catalogRegistry: com.riffle.core.catalog.CatalogRegistry,
    @com.riffle.core.data.di.EpubDownloadsStore
    private val epubDownloadsStore: com.riffle.core.domain.LocalStore,
    @com.riffle.core.data.di.EpubCacheStore
    private val epubCacheStore: com.riffle.core.domain.LocalStore,
) : AndroidViewModel(application) {

    // ReadingSessionCoordinator's per-call enabled gate reads this atomic; init below flips it once
    // the active Catalog's capability set is known (issue #439). Starts false so a coordinator tick
    // that fires before init completes stays a no-op — the coordinator won't heartbeat/flush until
    // the capability is confirmed.
    private val readingSessionsEnabled = java.util.concurrent.atomic.AtomicBoolean(false)

    // Once-per-(VM, book) flag + helper for the caption-annotation sweep — both cleanup (dedup
    // duplicate caption HIGHLIGHTs) and legacy TYPE_IMAGE upgrade. Declared here (BEFORE the
    // init block that launches openBook via viewModelScope) so they're initialized in time —
    // property initializers run in declaration order, and a later declaration would leave these
    // null when the launched coroutine's Highlights-mode branch reads them (crash 2026-07-14).
    private val legacyImageUpgradeAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val captionHighlightUpgrader by lazy { CaptionHighlightUpgrader(annotationStore) }

    // Formatting/typography/auto-scroll orchestrator — constructed with viewModelScope so
    // teardown is deterministic (the orchestrator's coroutines cancel when the VM is cleared).
    private val formatting: FormattingSession = formattingSessionFactory.create(viewModelScope).also {
        it.setDeviceDensity(application.resources.displayMetrics.density)
    }

    // WakeLock controller — derives keepScreenOn from prefs + hands-free running states
    // (Auto-Scroll + Cadence, issue #403). Cadence's state is attached separately because
    // WakeLockController's factory takes only the AutoScroll flow.
    private val wakeLock: com.riffle.app.feature.reader.controllers.WakeLockController =
        wakeLockControllerFactory.create(viewModelScope, formatting.autoScrollState).also {
            it.attachCadenceState(cadenceController.state)
        }

    // Bookmark observation + page-bookmarked detection. onScheduleSync delegates to
    // scheduleAnnotationSync() which is resolved lazily at call time.
    private val bookmarks: com.riffle.app.feature.reader.controllers.BookmarksController =
        bookmarksControllerFactory.create(viewModelScope, onScheduleSync = { scheduleAnnotationSync() })

    // Search execution, debounce, result navigation. Bound to the Publication once the book opens.
    private val search: com.riffle.app.feature.reader.controllers.SearchController =
        searchControllerFactory.create(viewModelScope)

    // Position orchestrator — owns the canonical reading-position stream and all hot-path state
    // (lastLocator, serverLocatorChannel, pendingServerJumpStamp, suppressNextServerLocator, etc.).
    private val position: PositionOrchestrator = positionOrchestratorFactory.create(viewModelScope)

    // Session lifecycle — owns Publication/epubFile/epubZip, matched-book cross-sync state
    // (readerSync + audiobookFollow + sourceId), and the close-sync guard. The VM keeps the
    // Compose-facing composer role: after lifecycle.open() returns Ready, the VM binds the
    // per-book collaborators (readaloud, position, bookmarks, annotationSession).
    private val lifecycle: com.riffle.app.feature.reader.session.ReaderSessionLifecycle =
        readerSessionLifecycleFactory.create(
            openPublication = { file -> openPublication(file) },
            cfiStringToLocator = { cfi -> cfiStringToLocator(cfi) },
        )

    // Readaloud session — owns readaloud state and leaf controls. Full extraction happens across
    // sub-tasks 8.1–8.5; each sub-task lifts more logic here from the VM.
    private val readaloud: com.riffle.app.feature.reader.session.ReadaloudSession =
        readaloudSessionFactory.create(
            scope = viewModelScope,
            snapshotLocator = { position.snapshotLastLocator() },
        ).also { session ->
            // Option-B provider seam (sub-task 8.2): session reads VM's live vars without setters.
            // itemId is wired in init {} because it is declared after readaloud in the class body.
            session.readerSyncProvider = { lifecycle.matchedSync.value?.readerSync }
            session.audiobookFollowProvider = { lifecycle.matchedSync.value?.audiobookFollow }
        }

    // Annotation session — owns highlight/annotation state, panel visibility, navigation channel,
    // sync lifecycle (syncOnOpen, live-pull loop, syncOnClose). Publication-dependent operations
    // (createHighlight, toggleBookmark, CFI build, annotationToRender) stay in the VM and are
    // injected as resolver lambdas at bind() time so the session stays Readium-import-free.
    private val annotationSession: com.riffle.app.feature.reader.session.AnnotationSession =
        annotationSessionFactory.create(
            scope = viewModelScope,
            startLiveSync = { sid, ns, iid ->
                annotationSyncController.startLiveSync(sid, ns, iid)
            },
            scheduleSync = { sid, ns, iid ->
                annotationSyncController.scheduleDebounce(sid, ns, iid)
            },
            syncOnOpen = { sid, ns, iid ->
                annotationSyncController.syncOnOpen(sid, ns, iid)
            },
            syncOnClose = { sid, ns, iid ->
                annotationSyncController.syncOnClose(sid, ns, iid)
            },
            mergeAfterEdit = { id, color, note ->
                mergeAdjacentIntoHighlight(id, color, note)
            },
        )

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    // Which content this reader instance displays (ADR 0041). Nav args carry `?source=highlights`
    // (lowercase, see MainScreen's EPUB_READER route); ReaderSource's canonical form is
    // uppercase-first ("Highlights"). See [decodeReaderSource] for why matching is case-insensitive
    // rather than single-character-normalised.
    private val source: ReaderSource = decodeReaderSource(savedStateHandle.get<String>("source"))

    /** Public read of [source] for the screen layer to gate Readaloud/Rail UI (Task 9, ADR 0041). */
    val readerSource: ReaderSource get() = source

    // Audiobook→readaloud handoff: when opened by swiping the audiobook player down, this carries the
    // audiobook's current position (seconds on the concatenated timeline; -1 = not a handoff). On open
    // we auto-start readaloud playing from this second so narration continues where listening left off.
    // Also observed as a flow so a back-stack return (reader was alive behind the audiobook player) can
    // receive an updated value set by the audiobook player when it pops back.
    private val startReadaloudAtSec: Double =
        (savedStateHandle.get<Float>("startReadaloudAtSec") ?: -1f).toDouble()

    // When opened from a library annotation search result, jump to this CFI instead of the saved
    // reading position. Null/blank for a normal open. EPUB-only (annotations anchor on ABS-EPUB CFI).
    private val openAtCfi: String? = savedStateHandle.get<String>("openAtCfi")

    // TOC entry to open immediately on launch — navigated once the publication is ready, using the same
    // _navigationEvents channel as the TOC panel's tap handler (see navigateToEntry).
    private val startTocHref: String? = savedStateHandle["startTocHref"]

    // Highlights mode only (ADR 0041): the server the tapped book's highlights belong to, threaded
    // explicitly through the nav route by AnnotationsListScreen's onBookClick rather than re-resolved
    // from "the active server" at open time. Without this, a Server Switcher change racing the
    // Annotations-list → reader navigation would open the elided reader against whatever server
    // happens to be active when openBook() runs, not the server the tapped book's highlights are
    // actually stored under — silently showing zero highlights. Null (and thus falling back to the
    // active server) for every other nav origin, including a normal FullBook open.
    private val navServerId: String? = savedStateHandle.get<String>("sourceId")

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    private val _footnotePopup = MutableStateFlow<FootnotePopupState?>(null)
    val footnotePopup: StateFlow<FootnotePopupState?> = _footnotePopup

    // Fullscreen figure-zoom overlay state (feature: pressing on a figure opens it in a zoomed
    // viewer with dimmed background). Set from a JS hit-test payload posted by FigureTapScript;
    // reset when the user dismisses the overlay (tap-outside / system back).
    private val _figureZoom = MutableStateFlow<FigureZoomState?>(null)
    internal val figureZoom: StateFlow<FigureZoomState?> = _figureZoom

    /** Called by the reader glue with the raw JSON emitted by figure-tap.js. Parses and opens the
     *  overlay; malformed payloads are silently ignored (the JS may drift ahead of Kotlin). */
    fun onFigureTapPayload(payload: String) {
        val state = FigureTapMessageParser.parse(payload) ?: return
        _figureZoom.value = state
    }

    /** Dismiss the figure-zoom overlay (user tapped outside, pressed Back, etc). Idempotent. */
    fun dismissFigureZoom() {
        _figureZoom.value = null
    }

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

    // Highlights mode (ADR 0041) live DOM patch bus. Each per-annotation store change turns into
    // one [HighlightsDomPatch]; the screen collects and dispatches through the RendererBridge.
    // BUFFERED replay=0 — patches are stateless deltas; on subscribe we don't want stale events.
    // The extra buffer keeps rapid bursts (e.g. sync inserts N highlights) from suspending the
    // observer coroutine before the screen collector runs its first tick.
    private val _highlightDomPatches =
        MutableSharedFlow<com.riffle.app.feature.reader.highlights.HighlightsDomPatch>(
            extraBufferCapacity = 32,
        )
    val highlightDomPatches: SharedFlow<com.riffle.app.feature.reader.highlights.HighlightsDomPatch> =
        _highlightDomPatches.asSharedFlow()

    // Nav events the screen can't service itself — e.g. leaving the elided Highlights-mode reader
    // to open the real source book at a tapped highlight's CFI (Task 9, ADR 0041).
    private val _readerNavEvents = Channel<ReaderNavEvent>(Channel.BUFFERED)
    val readerNavEvents: Flow<ReaderNavEvent> = _readerNavEvents.receiveAsFlow()

    // Delegates to the session — the channel lives there now (sub-task 8.3).
    val pageTopProbeRequests: Flow<String> get() = readaloud.pageTopProbeRequests

    // Shared sync seam — same construction pattern in every reader ViewModel (#528).
    private val syncSession = ProgressSyncController(
        itemId = itemId,
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val readingSessionCoordinator = ReadingSessionCoordinator(
        clock = clock,
        readingSpeedStore = readingSpeedStore,
        scope = viewModelScope,
        enabled = { readingSessionsEnabled.get() },
    )

    private val positionSaveCoordinator = PositionSaveCoordinator<String>(
        savePosition = { cfi ->
            epubRepository.saveReadingPosition(itemId, cfi)
            // Matched book: reading is also listening — persist the translated audiobook position
            // locally so the durable sweep pushes the audio record too, without reopening (ADR 0030).
            readaloud.mirrorReadingToAudiobook(cfi)
        },
        updateProgress = { progress -> updateReadingProgressUseCase(itemId, progress) },
    )

    // ---- PositionOrchestrator delegations ---------------------------------------------------

    val serverLocatorEvents: Flow<Locator> = position.serverLocatorEvents
    val currentLocatorHref: StateFlow<String?> = position.currentLocatorHref
    val currentLocatorProgression: StateFlow<Float?> = position.currentLocatorProgression
    val currentLocatorTotalProgression: StateFlow<Float?> = position.currentLocatorTotalProgression
    val latestLocator: Locator? get() = position.snapshotLastLocator()

    // -----------------------------------------------------------------------------------------

    // publication, epubFile, epubZip, closeSyncDone, readerSync, audiobookFollow, readerSyncServerId
    // now live on [lifecycle] (issue #376). Reads route through lifecycle.publication.value and
    // lifecycle.matchedSync.value; teardown via lifecycle.onCleared().
    private val chapterHtmlCache = mutableMapOf<Int, String>()

    // Highlights mode only (Task 10, ADR 0041): the chapter grouping + resolved server id from the
    // last [openBook] build, kept so the resume-position collector below can map a synthesised-href
    // locator update back to a highlight id without rebuilding the Publication. Null in FullBook mode.
    private var highlightsResumeChapters: List<ChapterElision>? = null
    private var highlightsResumeServerId: String? = null
    // Plurality [AnnotationEntity.originFontFamily] across the currently-loaded elided chapters
    // (issue #484). Recomputed on every full elided rebuild in [loadHighlightsPublication] and
    // re-used as the `bookBodyFontFamily` fallback on partial re-renders (post-edit setChapterBytes)
    // so a chapter refreshed after a recolour keeps the same fallback face as its neighbours. Null
    // when no annotation on the book has a captured font yet — factory then emits no
    // font-family and the elided reader falls back to ReadiumCSS default (pre-fix behaviour).
    private var elidedBodyFontFamily: String? = null
    // Highlights mode only: subscription to annotationStore.observeAnnotations so the elided
    // reader updates live when annotations change (colour/note/delete edits from anywhere, and
    // additions arriving via AnnotationSyncController's remote pull). Kept alive across the
    // per-chapter openBook() reruns that a structural rebuild triggers.
    private var highlightsObserverJob: Job? = null
    // Full snapshot of each annotation currently rendered in the elided reader — keyed by id.
    // The observer compares an incoming emission against this snapshot per-id to decide whether
    // to emit a targeted [HighlightsDomPatch] (colour/note/delete, in-chapter add) or fall back
    // to a full [reloadHighlightsView] rebuild (new-chapter add, chapter emptied, spine shape
    // change). Updated at the end of every openBook() and after each successful patch dispatch.
    private var highlightsRenderedById: Map<String, AnnotationEntity> = emptyMap()
    // Handle to the currently-loaded Highlights Publication, kept so per-annotation patches can
    // rewrite ONE chapter's synthesised HTML in place (see [HighlightsPublicationHandle.setChapterBytes])
    // — so a subsequent chapter-back navigation still renders the fresh state without a rebuild.
    private var highlightsPublicationHandle:
        com.riffle.app.feature.reader.highlights.HighlightsPublicationHandle? = null
    // True after the navigator emits its first locator (the restored position on open).
    // The first emission is not new user progress — the position is already in DB — so we skip
    // the DB write to avoid stomping localUpdatedAt before the initial sync cycle runs.
    // Snapshot of the reading position taken when a FootnotePopup is shown; the popup's link-tap path
    // (see EpubReaderScreen + captureFootnotePopupLinkOrigin) promotes this into the pending field
    // before the external browser launches. Capturing here — not at link-tap — avoids reading a
    // [lastLocator] that the popup's overlay layout has already nudged off the user's page.
    private var footnotePopupOriginLocator: Locator? = null

    // Hold the screen on whenever EITHER the global preference says to OR Auto-Scroll is running
    // (ADR 0037 — a sleeping screen would visibly break a hands-free session).
    val keepScreenOn: StateFlow<Boolean> = wakeLock.keepScreenOn

    // ---- FormattingSession delegations ---------------------------------------------------------

    val autoScrollState: StateFlow<com.riffle.core.domain.autoscroll.AutoScrollState> =
        formatting.autoScrollState

    val autoScrollScrollDeltas: kotlinx.coroutines.flow.Flow<Int> = formatting.autoScrollScrollDeltas

    fun setAutoScrollPaused(paused: Boolean, cause: com.riffle.core.domain.autoscroll.PauseCause) =
        formatting.setAutoScrollPaused(paused, cause)

    fun reachedEndOfBookForAutoScroll() = formatting.reachedEndOfBookForAutoScroll()

    fun startAutoScroll() {
        // Three-way mutual exclusion via the pure arbiter (ADR 0037 + issue #403). Compute the
        // fan-out first, apply it, then start the local feature. Routing through the arbiter
        // keeps the "who pauses whom" truth-table in one place — the reducer stays feature-local.
        applyArbiter(com.riffle.core.domain.cadence.Feature.AutoScroll)
        formatting.startAutoScroll()
    }

    fun stopAutoScroll() = formatting.stopAutoScroll()

    fun nudgeAutoScroll(by: Int) = formatting.nudgeAutoScroll(itemId, by)

    fun pauseAutoScroll(cause: com.riffle.core.domain.autoscroll.PauseCause) =
        formatting.pauseAutoScroll(cause)

    fun resumeAutoScrollIfPaused() = formatting.resumeAutoScrollIfPaused()

    fun pauseAutoScrollFromPill() = formatting.pauseAutoScrollFromPill()

    fun resumeAutoScrollFromPill() = formatting.resumeAutoScrollIfPaused()

    fun setReaderViewportWidthPx(px: Int) = formatting.setViewportWidthPx(px)

    // ---- Cadence (issue #403 / ADR 0040) --------------------------------------------------------

    val cadenceState: StateFlow<com.riffle.core.domain.cadence.CadenceState> = cadenceController.state
    val cadenceCurrentFragment: StateFlow<String?> = cadenceController.currentFragment

    private val _cadenceQuotes = MutableStateFlow<Map<String, com.riffle.core.domain.SentenceQuote>>(emptyMap())
    val cadenceQuotes: StateFlow<Map<String, com.riffle.core.domain.SentenceQuote>> = _cadenceQuotes.asStateFlow()

    /** True iff `Intl.Segmenter` is available in the reader WebView — the top-bar toggle hides when false. */
    private val _cadencePlatformSupported = MutableStateFlow(true)
    val cadencePlatformSupported: StateFlow<Boolean> = _cadencePlatformSupported.asStateFlow()

    /**
     * Fires when Cadence exhausts the current chapter. The reader screen observes this and asks
     * its active presenter (Continuous / Readium) to advance one chapter forward, then re-runs the
     * DOM tokenisation for the new chapter — see [onCadenceChapterTokenised].
     */
    private val _cadenceEndOfChapterEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cadenceEndOfChapterEvents: SharedFlow<Unit> = _cadenceEndOfChapterEvents.asSharedFlow()

    /**
     * Called by the reader screen after JS returns the tokenised chapter's sentence map.
     * Rebinding a new source replaces the previous chapter's Cadence session — the ticker
     * discards its state, the fragment ordering is refreshed, and the next Start begins at s0.
     *
     * Empty maps are still bound so the top-bar toggle can appear on chapters that happen to have
     * zero readable sentences (unusual — front-matter blank pages); Cadence's onExhausted fires
     * immediately in that case and the reader advances to the next chapter.
     */
    fun onCadenceChapterTokenised(
        quotes: Map<String, com.riffle.core.domain.SentenceQuote>,
        hrefs: Map<String, String>,
    ) {
        // Accumulate quotes across every tokenised chapter — Continuous keeps several chapters
        // loaded at once in the sliding window, and paginated may re-tokenise the current one on
        // reflow. LinkedHashMap preserves insertion order so the ticker's fragment ordering stays
        // reading-order stable across rebinds.
        val merged = LinkedHashMap(_cadenceQuotes.value)
        merged.putAll(quotes)
        _cadenceQuotes.value = merged
        val mergedHrefs = LinkedHashMap(_cadenceChapterHrefs)
        mergedHrefs.putAll(hrefs)
        _cadenceChapterHrefs = mergedHrefs
        logger.d(com.riffle.core.logging.LogChannel.Cadence) {
            "VM.onCadenceChapterTokenised chapterQuotes=${quotes.size} totalQuotes=${merged.size}"
        }
        val source = com.riffle.app.feature.reader.cadence.DomSentenceSource().apply {
            supplyResult(merged, mergedHrefs)
        }
        viewModelScope.launch {
            cadenceController.bind(source, onExhausted = {
                _cadenceEndOfChapterEvents.tryEmit(Unit)
            })
        }
    }

    private var _cadenceChapterHrefs: Map<String, String> = emptyMap()

    /**
     * Report the WebView's Intl.Segmenter feature-detect result. Updates the in-memory flag that
     * drives the reader's top-bar toggle AND persists the same flag to [FormattingPreferencesStore]
     * so the Settings screen (which can be opened before any book) hides its Cadence entry when
     * the current device's WebView doesn't support the feature.
     */
    fun setCadencePlatformSupported(supported: Boolean) {
        _cadencePlatformSupported.value = supported
        formatting.persistCadencePlatformSupported(supported)
    }

    /**
     * Carries chapter-href requests to the reader screen so it can probe the current page-top
     * sentence against the WebView. Mirrors Readaloud's `pageTopProbeRequests` (issue #403 spec:
     * "start from the sentence currently on screen — same as Readaloud"). The screen replies via
     * [onCadencePageTopResolved].
     */
    private val _cadencePageTopProbeChannel = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.CONFLATED,
    )
    val cadencePageTopProbeRequests: Flow<String> = _cadencePageTopProbeChannel.receiveAsFlow()

    /**
     * Called by the reader screen once the WebView resolves the first Cadence-tokenised sentence
     * visible on [href]'s current page (`fragmentId` = "cd-N", or null when none could be
     * located). Starts Cadence from there.
     *
     * When the probe fails ([fragmentId] is null/blank) we fall back to the first fragment of
     * the CURRENT chapter — see [resolveCadenceStartRef]. Falling through to the ticker's own
     * "startIndex = 0" default would jump to cd-0 of the merged cross-chapter history, i.e. the
     * first sentence of whichever chapter was tokenised first this session — usually several
     * pages behind the user, which then triggers a Readium auto-scroll to the decoration.
     */
    fun onCadencePageTopResolved(href: String, fragmentId: String?) {
        val startRef = resolveCadenceStartRef(
            href = href,
            probedFragmentId = fragmentId,
            chapterHrefs = _cadenceChapterHrefs,
            knownRefs = _cadenceQuotes.value.keys,
        )
        logger.d(com.riffle.core.logging.LogChannel.Cadence) {
            "VM.onCadencePageTopResolved href=$href fragmentId=$fragmentId → startRef=$startRef"
        }
        if (startRef != null) cadenceController.goTo(startRef)
        cadenceController.dispatch(com.riffle.core.domain.cadence.CadenceEvent.Start)
    }

    /**
     * Start Cadence — pauses Readaloud and Auto-Scroll first (mutual exclusion per issue #403),
     * then emits a page-top probe request. The reader screen resolves the current page's first
     * visible sentence and replies via [onCadencePageTopResolved], which finally dispatches the
     * Start event. Mirrors Readaloud's `startReadaloud` flow — same plumbing, same semantics.
     */
    fun startCadence() {
        applyArbiter(com.riffle.core.domain.cadence.Feature.Cadence)
        val href = position.currentLocatorHref.value
        logger.d(com.riffle.core.logging.LogChannel.Cadence) {
            "VM.startCadence currentLocatorHref=$href tokenisedChapters=${_cadenceChapterHrefs.values.distinct().size}"
        }
        if (href == null) {
            // No known locator yet — dispatch Start directly; ticker falls back to cd-0.
            cadenceController.dispatch(com.riffle.core.domain.cadence.CadenceEvent.Start)
            return
        }
        _cadencePageTopProbeChannel.trySend(href)
    }

    fun stopCadence() =
        cadenceController.dispatch(com.riffle.core.domain.cadence.CadenceEvent.Stop)

    fun nudgeCadence(by: Int) =
        cadenceController.dispatch(com.riffle.core.domain.cadence.CadenceEvent.NudgeSpeed(by))

    fun pauseCadence(cause: com.riffle.core.domain.cadence.PauseCause) =
        cadenceController.pauseFor(cause)

    fun resumeCadenceIfPaused() =
        cadenceController.dispatch(com.riffle.core.domain.cadence.CadenceEvent.Resume)

    fun setCadencePaused(
        paused: Boolean,
        cause: com.riffle.core.domain.cadence.PauseCause,
    ) = cadenceController.setPaused(paused, cause)

    /**
     * Snapshot the currently-running feature and apply [com.riffle.core.domain.cadence.onStart]'s
     * pause fan-out. Called before dispatching a Start event to [starting]'s own controller.
     *
     * The current-feature snapshot has an at-most-one invariant guaranteed by the arbiter itself
     * — the last successful startX() call would have paused any prior running feature. In the
     * event of a race we pick the highest-priority winner (Cadence > AutoScroll > Readaloud) so
     * the pause fan-out is deterministic.
     */
    private fun applyArbiter(starting: com.riffle.core.domain.cadence.Feature) {
        val current = when {
            cadenceController.state.value is com.riffle.core.domain.cadence.CadenceState.Running ->
                com.riffle.core.domain.cadence.Feature.Cadence
            formatting.autoScrollState.value is com.riffle.core.domain.autoscroll.AutoScrollState.Running ->
                com.riffle.core.domain.cadence.Feature.AutoScroll
            playerCoordinator.state.value.connected && playerCoordinator.state.value.isPlaying ->
                com.riffle.core.domain.cadence.Feature.Readaloud
            else -> com.riffle.core.domain.cadence.Feature.None
        }
        val action = com.riffle.core.domain.cadence.onStart(current, starting)
        if (action.pauseAutoScroll) formatting.stopAutoScroll()
        if (action.pauseReadaloud) playerCoordinator.pause()
        if (action.pauseCadence) {
            val cause = when (starting) {
                com.riffle.core.domain.cadence.Feature.AutoScroll ->
                    com.riffle.core.domain.cadence.PauseCause.AutoScrollStarted
                com.riffle.core.domain.cadence.Feature.Readaloud ->
                    com.riffle.core.domain.cadence.PauseCause.ReadaloudStarted
                else -> com.riffle.core.domain.cadence.PauseCause.PanelOpen
            }
            cadenceController.pauseFor(cause)
        }
    }

    // ---- VolumeKeyDispatcher delegations -----------------------------------------------------------

    val volumeKeyNavigationEnabled = volumeKeyDispatcher.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys = volumeKeyDispatcher.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val skipIntervalSec: StateFlow<Double> = listeningPreferencesStore.skipIntervalSeconds
        .map { it.toDouble() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS.toDouble())

    val rewindIntervalSec: StateFlow<Double> = listeningPreferencesStore.rewindIntervalSeconds
        .map { it.toDouble() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS.toDouble())

    private val rewindOnResumeSec: StateFlow<Double> = listeningPreferencesStore.rewindOnResumeSeconds
        .map { it.toDouble() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS.toDouble())

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeKeyDispatcher.volumeNavEvents

    // Raw user-picked prefs (theme = Auto stays as Auto) — feeds the FormattingPanel chip selection.
    val formattingPreferences: StateFlow<FormattingPreferences> = formatting.formattingPreferences

    // Resolved prefs (Auto theme replaced by concrete colour) — feeds Readium and the palette.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> =
        formatting.effectiveFormattingPreferences

    val hasBookOverrides: StateFlow<Boolean> = formatting.hasBookOverrides

    val formattingPreferencesReady: StateFlow<Boolean> = formatting.formattingPreferencesReady

    // ---- SearchController delegations -----------------------------------------------------------

    val isSearchActive: StateFlow<Boolean> = search.isSearchActive
    val searchQuery: StateFlow<String> = search.searchQuery
    val searchResults: StateFlow<List<Locator>> = search.searchResults
    val currentSearchIndex: StateFlow<Int> = search.currentSearchIndex
    val searchNavigationEvents: Flow<Locator> = search.searchNavigationEvents

    // ---- Readaloud (ADR 0023) ----------------------------------------------------------------

    // isStorytellerService and readerServerId now live on [lifecycle] (issue #376). The single
    // remaining read site (annotation binding) captures activeServer + isStorytellerService from
    // the Ready payload directly.

    // ---- Annotations (ADR 0024 / 0025) -------------------------------------------------------

    // The ABS server hosting this item; annotations key on it together with itemId. Resolved once
    // the active server is known. Null on the Storyteller-only / Readaloud side, where annotations
    // are absent.
    private var annotationServerId: String? = null

    // ABS-side stable account identity for [annotationServerId], used by AnnotationSyncController
    // as the cross-device sync namespace (the local servers.id is a per-device UUID and would
    // hide other devices' files). Resolved alongside [annotationServerId] on book open;
    // remains null if the active server isn't ABS, the row hasn't been backfilled, or the
    // backfill /api/me call fails offline — in which case sync is skipped, not broken.
    private var annotationNamespace: String? = null

    // ---- AnnotationSession delegations -------------------------------------------------------

    // Highlights exist only while reading the ABS side. False on a Storyteller-only book — the
    // "Highlight" affordance must not appear there (ADR 0024).
    val annotationsAvailable: StateFlow<Boolean> = annotationSession.annotationsAvailable

    /**
     * A persisted highlight reconstructed into a renderable Readium locator + colour token +
     * optional note.
     *
     * [useAccentBarStyle] is Highlights-mode only (ADR 0041): when true,
     * [com.riffle.app.feature.reader.ReadiumHighlightRenderer.applyAnnotations] paints via
     * the accent-bar rendering path (synthesised HTML border-left + injected tap span) instead of the usual tinted style,
     * which renders no fill and confines Readium's tap hit-testing to a narrow gutter strip on
     * the left of the paragraph — taps in the middle of highlighted text fall through to the
     * immersive-mode toggle. FullBook mode leaves it false → normal tinted painting on the whole
     * selection.
     */
    data class HighlightRender(
        val id: String,
        val locator: Locator,
        val color: String,
        val note: String?,
        val useAccentBarStyle: Boolean = false,
    )

    val highlightRenders: StateFlow<List<HighlightRender>> = annotationSession.highlightRenders

    data class HighlightEditTarget(val id: String, val anchorRect: IntRect, val noteOnly: Boolean = false)

    /** Highlight whose actions popup should be open (just-created or tapped), else null. */
    val highlightToEdit: StateFlow<HighlightEditTarget?> = annotationSession.highlightToEdit

    fun openHighlightActions(id: String, anchorRect: IntRect) =
        annotationSession.openHighlightActions(annotationIdOf(id), anchorRect)

    /** Opens the popup in note-only read mode (no colour pickers, no delete). Used by the margin glyph. */
    fun openNoteReader(id: String, anchorRect: IntRect) =
        annotationSession.openNoteReader(annotationIdOf(id), anchorRect)

    /**
     * Strip the segment suffix (`#segN`) from a decoration id so tap dispatch resolves to the
     * underlying annotation. A cross-figure highlight paints as multiple decorations — one per
     * text segment either side of the figure — with ids `"<annotationId>#seg0"`, `"…#seg1"`, …;
     * see `annotationToRender`. Non-segmented decorations pass through unchanged.
     */
    private fun annotationIdOf(decorationId: String): String = decorationId.substringBefore('#')

    fun dismissHighlightActions() = annotationSession.dismissHighlightActions()

    /** Note-editor target — non-null while the note-editor dialog is open. */
    val noteEditorTarget: StateFlow<HighlightEditTarget?> = annotationSession.noteEditorTarget

    fun openNoteEditor(id: String, anchorRect: IntRect) =
        annotationSession.openNoteEditor(id, anchorRect)

    fun commitNoteEdit(id: String, note: String?) {
        viewModelScope.launch { annotationSession.commitNoteEdit(id, note) }
    }

    fun cancelNoteEdit() = annotationSession.cancelNoteEdit()

    // ---- Readaloud state delegations (state now lives in the session) -----------------------

    val readaloudAvailable: StateFlow<Boolean> get() = readaloud.readaloudAvailable

    val readaloudVisible: StateFlow<Boolean> get() = readaloud.readaloudVisible

    val readaloudOpen: StateFlow<Boolean> get() = readaloud.readaloudOpen

    val audiobookItemId: StateFlow<String?> get() = readaloud.audiobookItemId

    // Mirrors the controller's playback state for the mini-player controls.
    val playbackState: StateFlow<ReadaloudController.PlaybackState> = playerCoordinator.state

    // The text fragment currently narrated — drives the synced highlight. Null clears it.
    val activeFragmentRef: StateFlow<String?> = playerCoordinator.activeFragmentRef

    // How far the live position has advanced through the narrated sentence — drives intra-sentence
    // page turns when a sentence spans more than one paginated column.
    val narrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?> = playerCoordinator.narrationProgress

    /**
     * Cadence's analogue of [narrationProgress]: fuses the current cd-N fragment with the
     * ticker's dwell-progress fraction so the intra-column-follow LaunchedEffect can drive
     * page turns mid-sentence in paginated mode the same way Readaloud does. When Cadence
     * isn't running this stays null and the screen's mutex-OR selects Readaloud's flow instead.
     *
     * Reuses [PlayerCoordinator.NarrationProgress] verbatim — the intra-column-follow effect
     * only cares about `(fragmentRef, fraction)` and treats both drivers identically. Any short
     * `cd-N` sentence whose text wraps across the column boundary will get its second half
     * revealed on the next column just before the ticker advances — same behaviour readers
     * already have with Readaloud.
     */
    val cadenceNarrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?> = combine(
        cadenceController.currentFragment,
        cadenceController.currentProgress,
    ) { ref, fraction ->
        if (ref != null && fraction != null) PlayerCoordinator.NarrationProgress(ref, fraction) else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- Readaloud state delegations (state now lives in the session — sub-task 8.3) -------------

    val sentenceQuotes: StateFlow<Map<String, com.riffle.core.domain.SentenceQuote>> get() = readaloud.sentenceQuotes

    val readaloudHighlightColor: StateFlow<HighlightColor>
        get() = readaloud.readaloudHighlightColor

    val sentenceChapters: StateFlow<Map<String, String>> get() = readaloud.sentenceChapters

    val downloadPromptBytes: StateFlow<Long?> get() = readaloud.downloadPromptBytes

    val readaloudBarMessage: StateFlow<String?> get() = readaloud.readaloudBarMessage

    val downloadProgress: StateFlow<Float?> get() = readaloud.downloadProgress

    init {
        // Propagate isPlaying changes from the session to ReaderStateHolder (volume-key routing).
        // The sentence-quote build on isPlaying is now handled by the session's own init observer.
        readaloud.onAudioPlayingChanged = { isPlaying -> readerStateHolder.isAudioPlaying = isPlaying }
        // Wire the Storyteller pulled-locator callback once — position is constructed by this point.
        readaloud.storytellerServerLocatorCallback = { locator -> position.requestServerJump(locator) }
        // Issue #439: ReadingSessionCoordinator no-ops until the active Source is confirmed to
        // provide ReadingSessionsCapability. LocalFiles (no capability) never enables sessions;
        // ABS flips it on once its Catalog resolves.
        viewModelScope.launch {
            // Key on the book's own Source, not the currently-active one — a reader can outlive
            // a Source switch (issue #439 / ADR 0041). Fall back to the active Source when nav
            // didn't carry a sourceId (normal FullBook open), matching the resolution pattern
            // used elsewhere in this VM. Raw `is` check in place of the inline has<T>() extension —
            // see LibraryItemsViewModel.tabVisibility for the JVM-target rationale.
            val sourceId = navServerId ?: sourceRepository.getActive()?.id
            val catalog = sourceId?.let { catalogRegistry.forSourceId(it) }
            readingSessionsEnabled.set(catalog is com.riffle.core.catalog.ReadingSessionsCapability)
        }
        viewModelScope.launch {
            // Sequential: formatting prefs must be available before openBook() so the
            // navigator never sees the stateIn default on first paint (FormattingSession.bindToBook
            // waits for effectiveFormattingPreferences to reflect the loaded value).
            formatting.bindToBook(itemId, source.toFormattingScope())
            openBook()
        }
        // Readaloud start ⇒ stop Auto-Scroll (mutual exclusion, ADR 0037). Stop (not Pause):
        // pausing would leave an invisible Auto-Scroll session waiting to silently resume on
        // Readaloud stop — the surprise the ADR was written to head off.
        viewModelScope.launch {
            playerCoordinator.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    formatting.onPlaybackStateChanged(playing)
                    // Cadence half of the mutual-exclusion pair (issue #403): Readaloud playing
                    // pauses a running Cadence. Pause (not Stop): a Readaloud tap-pause should
                    // let the user resume Cadence exactly where it was.
                    if (playing) {
                        cadenceController.pauseFor(
                            com.riffle.core.domain.cadence.PauseCause.ReadaloudStarted,
                        )
                    }
                }
        }
        // Panel-open pause/resume is driven from the screen layer (it knows about
        // showFormattingPanel / tocVisible / annotationsPanelVisible / isSearchActive),
        // see [setAutoScrollPaused] below.
        viewModelScope.launch {
            if (!shouldRunReadingSideEffects(source)) return@launch
            syncSession.serverPositionEvents.collect { serverProgress ->
                val locator = serverProgressToLocator(serverProgress) ?: return@collect
                // An explicit openAtCfi (annotation tap / search hit) takes precedence over server
                // sync on first open — otherwise ABS's last-read position races in and yanks the
                // reader away from the annotation to wherever the user was reading last.
                // Suppress/emit logic delegated to the orchestrator.
                position.requestServerJumpWithSuppressCheck(locator)
            }
        }
        // Highlights mode only (Task 10, ADR 0041): persist the highlight the reader is currently
        // showing so reopening the book resumes near it. Device-local — never synced. Chapter-level
        // precision only: see [highlightsResumeAnnotationIdForHref]'s docstring for why a stable
        // per-highlight href isn't available.
        viewModelScope.launch {
            if (source != ReaderSource.Highlights) return@launch
            highlightsResumeHrefUpdates(position.currentLocator.mapNotNull { it?.href?.toString() })
                .collect { href ->
                    val sourceId = highlightsResumeServerId ?: return@collect
                    val chapters = highlightsResumeChapters ?: return@collect
                    val annotationId = highlightsResumeAnnotationIdForHref(chapters, href) ?: return@collect
                    highlightsResumeStore.setLastHighlightId(sourceId, itemId, annotationId)
                }
        }
        // NOTE: The sentence-quote build on isPlaying and the audiobook-follow push loop are now
        // owned by ReadaloudSession's own init block (sub-task 8.4). The isPlaying→ReaderStateHolder
        // bridge is wired above via readaloud.onAudioPlayingChanged.
        // NOTE: Source resolution + readaloud.bind() + annotation bind() are all done inside
        // openBook() (Option α), so they run sequentially in a single suspending coroutine.
    }

    private suspend fun openBook() {
        // Highlights mode (ADR 0041): the reader displays a synthesised, elided Publication built
        // from this book's stored highlights rather than the real ABS EPUB container. Diverted
        // before lifecycle.open() so the ABS fetch, matched-sync resolution, readaloud binding, and
        // annotation-sync wiring (all Task 8/9 concerns) never run for this reader instance.
        if (source == ReaderSource.Highlights) {
            val sourceId = navServerId ?: sourceRepository.getActive()?.id
            if (sourceId == null) {
                _state.value = ReaderState.Error("No active source")
                return
            }
            // Duplicate-caption cleanup (2026-07-14, follow-up to the caption-annotation shape).
            // FullBook's onOpenReady runs the full sweep, but Highlights mode is opened directly
            // from the Annotations tab and never hits onOpenReady — without this pass, duplicate
            // caption HIGHLIGHTs the FullBook path could produce stay stacked in the elided view
            // ("the captions are still doubled" reproduced on AVD 5554). Fires once per (VM,
            // book) via [legacyImageUpgradeAttempted]'s CAS, mirroring FullBook's own gate.
            if (legacyImageUpgradeAttempted.compareAndSet(false, true)) {
                val liveAnnotations = runCatching {
                    annotationStore.observeAnnotations(sourceId, itemId).first()
                }.getOrDefault(emptyList())
                val merged = runCatching {
                    captionHighlightUpgrader.cleanupDuplicatesOnly(liveAnnotations)
                }.getOrDefault(0)
                if (merged > 0) {
                    logger.d(LogChannel.HighlightMerge) {
                        "caption-highlight cleanup (highlights-mode) sourceId=$sourceId itemId=$itemId merged=$merged"
                    }
                    scheduleAnnotationSync()
                }
            }
            val rows = annotationDao.getForItem(sourceId, itemId)
            val toc = tocRepository.getCachedToc(sourceId, itemId)?.second.orEmpty()
            val chapters = buildChapterElisions(rows).mapIndexed { index, chapter ->
                chapter.copy(title = elidedChapterTitle(chapter.href, chapter.title, toc, index))
            }
            highlightsResumeChapters = chapters
            highlightsResumeServerId = sourceId
            // Snapshot what we just rendered so the observer can diff each incoming emission
            // per-annotation and pick the right response: targeted DOM patch for a colour/note/
            // delete/in-chapter-add, or a full rebuild for a structural change. Filter to
            // TYPE_HIGHLIGHT so this baseline matches the observer's own filter — otherwise every
            // bookmark on the book appears as a "removed" id on the first emission, triggers the
            // structural-change path, and reloadHighlightsView loops (open → observer → reload →
            // open → …) with a WebDAV syncOnOpen fired on every cycle (issue: elided-view infinite
            // load + repeated WebDAV pushes when the book has bookmarks).
            highlightsRenderedById = rows
                .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT }
                .associateBy { it.id }
            // Start observing (once per (sourceId, itemId)). Kept alive across the openBook()
            // reruns triggered by reloadHighlightsView, since cancelling and re-launching each
            // rebuild would drop emissions during the Loading→Ready gap.
            ensureHighlightsObserver(sourceId, itemId)
            // Book title composed as "<real title> — Annotations" so the reader's own top-bar
            // label makes clear which book's highlights are shown. Falls back to plain "Annotations"
            // when the local library_items row is gone (orphaned book).
            val realBookTitle = libraryItemDao.getById(sourceId, itemId)?.title
            // Fallback body-font for excerpts whose annotation has null `originFontFamily`
            // (legacy rows, W3C sync ingest). Pick the plurality font across the captured set —
            // it converges to the book's dominant face as the user creates new annotations.
            // Null (no captured fonts anywhere) → factory emits no `font-family`, falls all the
            // way back to ReadiumCSS default (pre-issue-484 behaviour). See issue #484 and
            // [HighlightsPublicationFactory.buildHandle].
            elidedBodyFontFamily = pluralityOriginFont(chapters)
            // Serve figure bytes from the source EPUB (if it's been downloaded) so annotated
            // figures render as their real image in the elided view instead of the "[figure
            // image not captured]" placeholder. Highlights mode never runs the normal
            // lifecycle.open() path, so the reader has no Readium `Publication` in hand —
            // ZipEpubResourceFetcher bypasses Readium and reads entries directly from the local
            // EPUB. Fetcher lives only across [buildHandle] since bytes are staged/encoded
            // during that call; safe to close immediately after.
            // Prefer the downloaded copy (persisted, guaranteed present); fall back to the cache
            // (short-lived, populated on every reader open for streaming books). Either works to
            // extract raster bytes for the elided view.
            val localEpub = epubDownloadsStore.get(sourceId, itemId)
                ?: epubCacheStore.get(sourceId, itemId)
            val figureFetcher = localEpub?.let(
                com.riffle.app.feature.reader.highlights.ZipEpubResourceFetcher::open,
            )
            val handle = try {
                highlightsPublicationFactory.buildHandle(
                    sourceId = sourceId,
                    itemId = itemId,
                    bookTitle = realBookTitle?.let { "$it — Annotations" },
                    chapters = chapters,
                    bookBodyFontFamily = elidedBodyFontFamily,
                    resourceFetcher = figureFetcher
                        ?: com.riffle.app.feature.reader.highlights.ResourceFetcher { null },
                )
            } finally {
                figureFetcher?.close()
            }
            highlightsPublicationHandle = handle
            val pub = handle.publication
            // Per-device resume (Task 10, ADR 0041): jump back to the chapter containing the
            // last-viewed highlight, at chapter-level precision. The synthesised Publication's
            // hrefs ("highlights/ch$index.xhtml") are rebuilt fresh every open from `chapters`'
            // index order, so resuming needs to re-derive the same index — there's no stable
            // per-highlight href to store directly.
            val lastId = highlightsResumeStore.lastHighlightId(sourceId, itemId)
            val resumeHref = lastId?.let { highlightsResumeChapterHref(chapters, it, pub.readingOrder.size) }
            val initialLocator = resumeHref?.let { href ->
                runCatching {
                    Locator.fromJSON(
                        JSONObject()
                            .put("href", href)
                            .put("type", "application/xhtml+xml"),
                    )
                }.getOrNull()
            }
            _state.value = ReaderState.Ready(
                publication = pub,
                title = pub.metadata.title ?: "Annotations",
                initialLocator = initialLocator,
            )
            // Bind the annotation session so the highlight-actions popup works in Highlights mode
            // (a prior gap: this branch used to return before binding at all, leaving
            // highlightRenders permanently empty and recolor/delete/note edits silent no-ops).
            // annotationServerId is read by scheduleAnnotationSync() (recolor/delete/note edits) and
            // by openHighlightInSourceBook()'s fallback.
            annotationServerId = sourceId
            // Same namespace resolution as the FullBook path (see onOpenReady) — recolor/delete/note
            // edits still need a real ABS account id to push through AnnotationSyncController's
            // scheduleDebounce; a blank namespace with a resolvable WebDAV target would otherwise
            // build a malformed remote path instead of cleanly no-op'ing (only a null target is a
            // documented no-op — see AnnotationSyncController.syncOnOpen/scheduleDebounce).
            val namespace = (sourceRepository.ensureSyncNamespace(sourceId)
                as? com.riffle.core.domain.SyncNamespace.Configured)?.value
            annotationNamespace = namespace
            annotationSession.bind(
                sourceId = sourceId,
                namespace = namespace ?: "",
                itemId = itemId,
                highlightRenderResolver = { a -> highlightsAnnotationToRender(chapters, a) },
                cfiLocatorResolver = { _ -> null },
            )
            return
        }
        val outcome = lifecycle.open(
            com.riffle.app.feature.reader.session.ReaderSessionLifecycle.OpenParams(
                itemId = itemId,
                openAtCfi = openAtCfi,
                startTocHref = startTocHref,
            ),
        )
        when (outcome) {
            is com.riffle.app.feature.reader.session.ReaderSessionLifecycle.OpenOutcome.Error ->
                _state.value = ReaderState.Error(outcome.message)
            is com.riffle.app.feature.reader.session.ReaderSessionLifecycle.OpenOutcome.Ready ->
                onOpenReady(outcome)
        }
    }

    private suspend fun onOpenReady(
        o: com.riffle.app.feature.reader.session.ReaderSessionLifecycle.OpenOutcome.Ready,
    ) {
        val pub = o.publication
        // Bind the search controller to the new publication so it can execute queries.
        search.bind(pub)

        // Mirror the resolved speed into the readaloud session before bind() so its startup state
        // uses the right value.
        readaloud.initialSpeed = o.resolvedInitialSpeed

        // ── Bind the readaloud session — must happen before position.bindBook() ────────
        // All per-book readaloud wiring is consolidated here (Option α). After this call,
        // availability flags are set and background work (pre-warm, sidecar, quotes) is launched.
        readaloud.bind(
            sourceId = o.resolvedReaderServerId ?: "",
            itemId = itemId,
            isStorytellerService = o.isStorytellerService,
            audioBookId = o.resolvedAudioBookId,
            audioServerId = o.resolvedAudioServerId,
            audioSettingsIdentity = o.resolvedAudioSettingsIdentity,
            audiobookItemId = o.resolvedAudiobookItemId,
            effectiveFormattingPreferencesFlow = formatting.effectiveFormattingPreferences,
            currentLocatorFlow = position.currentLocator,
            readerSyncProvider = { lifecycle.matchedSync.value?.readerSync },
            audiobookFollowProvider = { lifecycle.matchedSync.value?.audiobookFollow },
            readerSyncServerIdProvider = { lifecycle.matchedSync.value?.sourceId },
        )

        // Bind the position orchestrator so it can save positions for this book.
        position.bindBook(
            itemId = itemId,
            sourceId = o.resolvedReaderServerId ?: "",
            positionSaveCoordinator = positionSaveCoordinator,
            readingPositionStore = readingPositionStore,
            spinePositionCounts = spinePositionCounts,
        )
        // When openAtCfi resolved, an ABS server-progress event arriving right after open must not
        // stomp the explicit nav intent (annotation tap / search hit). Drop the first
        // server-locator that follows; subsequent syncs (peer / live progress) still apply normally.
        if (o.openAtLocator != null) position.markSuppressNextServerLocator()

        _state.value = ReaderState.Ready(
            publication = pub,
            title = o.title,
            initialLocator = o.initialLocator,
            initialFocusAnnotationId = o.initialFocusAnnotationId,
        )

        // Navigate to the requested TOC entry using the same path as an in-reader TOC tap.
        // formattingPrefsProvider in the nav event handler ensures the correct continuous vs paged
        // path is taken even when Compose state hasn't caught up yet.
        startTocHref?.let { navigateToEntry(TocEntry(title = "", href = it)) }

        // Paged-mode only: after initialLocator opens the right chapter, fire the locator through
        // the annotation-nav channel so the post-go() column-snap runs (the architectural invariant:
        // the paged reader never rests off-grid). Continuous / Vertical are intentionally excluded —
        // they land correctly without this snap.
        if (o.openAtLocator != null &&
            formatting.effectiveFormattingPreferences.value.orientation == ReaderOrientation.Horizontal
        ) {
            annotationSession.emitAnnotationNavigation(o.openAtLocator)
        }

        // Audiobook→readaloud handoff: opened by swiping the audiobook player down. Auto-start
        // readaloud from the audiobook's position so narration continues where listening stopped.
        if (startReadaloudAtSec >= 0.0 && readaloud.readaloudAvailable.value) {
            startReadaloudAtSecond(startReadaloudAtSec)
        }

        // ── Annotation binding — ABS-side only (ADR 0024) ────────────────────────────
        val activeServer = o.activeServer
        if (!o.isStorytellerService && activeServer != null) {
            annotationServerId = activeServer.id
            // Bind the bookmarks controller so it can observe bookmarks and track the current
            // locator for page-bookmark detection.
            bookmarks.bind(
                sourceId = activeServer.id,
                itemId = itemId,
                currentLocator = position.currentLocator,
                spinePositionCounts = spinePositionCounts,
                viewportFractionByHref = viewportFractionByHref,
            )
            // Push orientation changes into the controller via a setter so the page-bookmark
            // indicator's match window re-sizes on a mid-session flip.
            bookmarks.onOrientationChanged(formatting.formattingPreferences.value.orientation)
            viewModelScope.launch {
                formatting.formattingPreferences
                    .map { it.orientation }
                    .distinctUntilChanged()
                    .collect { bookmarks.onOrientationChanged(it) }
            }
            // Resolve the source's cross-device annotation-sync namespace (#529). Null when
            // the source is LocalOnly (Storyteller peer, anonymous catalog, local files) or its
            // remote id hasn't been fetched yet — sync is skipped this session; DB stays canonical.
            val namespace = (sourceRepository.ensureSyncNamespace(activeServer.id)
                as? com.riffle.core.domain.SyncNamespace.Configured)?.value
            annotationNamespace = namespace
            annotationSession.bind(
                sourceId = activeServer.id,
                namespace = namespace ?: "",
                itemId = itemId,
                highlightRenderResolver = { a -> annotationToRender(a) },
                cfiLocatorResolver = { cfi -> cfiStringToLocator(cfi) },
            )
            // Opportunistic caption-annotation upgrade (2026-07-14). For each legacy
            // TYPE_IMAGE annotation on this book whose figure and caption can both be resolved
            // against the current publication, rewrite it to a TYPE_HIGHLIGHT covering the
            // caption text. Fires once per (VM, book); the store update propagates through
            // the annotation Flow → session → decorations naturally.
            if (legacyImageUpgradeAttempted.compareAndSet(false, true)) {
                viewModelScope.launch {
                    val serverId = activeServer.id
                    val legacy = runCatching {
                        annotationStore.observeAnnotations(serverId, itemId)
                            .first()
                            .filter { it.type == AnnotationEntity.TYPE_IMAGE }
                    }.getOrDefault(emptyList())
                    if (legacy.isEmpty()) return@launch
                    val allAnnotations = runCatching {
                        annotationStore.observeAnnotations(serverId, itemId).first()
                    }.getOrDefault(emptyList())
                    val result = runCatching {
                        captionHighlightUpgrader.sweep(
                            annotations = allAnnotations,
                            readChapterHtml = { spineIndex -> readChapterHtml(spineIndex) },
                        )
                    }.getOrNull()
                    if (result != null && result.total > 0) {
                        logger.d(LogChannel.HighlightMerge) {
                            "caption-highlight sweep sourceId=$serverId itemId=$itemId " +
                                "merged=${result.merged} upgraded=${result.upgraded} legacy=${legacy.size}"
                        }
                        scheduleAnnotationSync()
                    }
                }
            }
        }

        // Sync immediately while localUpdatedAt is still the genuine stored value — before the
        // navigator restore emits and would stamp localUpdatedAt = now.
        val syncLocator = o.effectiveInitialLocator
        if (lifecycle.matchedSync.value?.readerSync != null) {
            runReaderSyncCycle(syncLocator)
        } else {
            syncSession.sync(syncLocator?.toPayload() ?: SessionPayload("", 0f))
        }

        // Heartbeat only — no speed-tracker baseline yet (openBook can fire well before the first
        // onReaderResumed; the lifecycle handler resets the baseline once the reader is visible).
        startReadingSession(initialTotalProgression = null)
    }

    private fun startReadingSession(initialTotalProgression: Float?) {
        if (!shouldRunReadingSideEffects(source)) return
        readingSessionCoordinator.onResumed(
            initialTotalProgression = initialTotalProgression,
            onTick = { syncCurrentPosition() },
        )
    }

    private fun syncCurrentPosition() {
        if (!shouldRunReadingSideEffects(source)) return
        val locator = position.snapshotLastLocator() ?: return
        viewModelScope.launch {
            if (lifecycle.matchedSync.value?.readerSync != null) runReaderSyncCycle(locator)
            else syncSession.sync(locator.toPayload())
        }
    }

    /**
     * One canonical reconciliation cycle (ADR 0019). The canonical position is the displayed-EPUB
     * reading position with its stored timestamp; a remote win jumps the reader (including a
     * genuinely-newer audiobook listened on another device, bridged through the bundle), and the
     * winning timestamp is persisted as the canonical localUpdatedAt. Any failure is isolated here.
     *
     * The audiobook is reconciled **both ways**: the cycle reads it (a cross-device listen wins and
     * moves the reader) and writes it when the reading position wins (reading advances it forward); a
     * separate follow loop (see init) also pushes it from the exact narrated sentence while readaloud
     * plays. Every write records the server's returned timestamp as localUpdatedAt — and a remote-win
     * jump keeps that adopted time instead of re-stamping `now` (pendingServerJumpStamp) — so our own
     * write never reads back as a newer remote and drives the ebook (the loop that erased progress).
     */
    private suspend fun runReaderSyncCycle(locator: Locator?) {
        val ms = lifecycle.matchedSync.value ?: return
        val coordinator = ms.readerSync ?: return
        val sourceId = ms.sourceId ?: return
        val locJson = (locator ?: position.snapshotLastLocator())?.toJSON()?.toString()
        if (locJson != null) {
            val localUpdatedAt = readingPositionStore.loadLocalUpdatedAt(sourceId, itemId)
            // While parked on the sentence readaloud stopped on, readaloud already wrote the precise
            // audiobook position; reconcile the audiobook inbound-only so this page-derived cycle can't
            // regress it to the page top (ADR 0031). Outbound resumes once the user navigates off the page.
            val pushAudio = readaloud.parkPolicy.fragmentRef == null
            val result = runCatching { coordinator.runCycle(locJson, localUpdatedAt, pushAudio) }.getOrNull()
            if (result != null) {
                result.jumpLocatorJson?.let { json ->
                    runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()?.let { loc ->
                        position.requestServerJump(loc)
                        // A server sync (e.g. a newer audiobook listen) moved the reader. Make the synced
                        // position the reader's position for EVERY readaloud-start input, not just the
                        // persisted resume row: the tracked locator, and the in-memory close/resume state
                        // the planner reads (both seeded at book-open, before this sync). Without this, a
                        // later "start readaloud" resumes the STALE pre-sync sentence and jumps the reader
                        // (and the synced ebook+audiobook) back to the old position — the erase.
                        // Update the in-memory snapshot so subsequent sync cycles use the jumped position;
                        // the real onPositionChanged from Readium (below) will handle persistence.
                        position.updateLastLocatorSnapshot(loc)
                        readaloud.clearCloseAndResumeForServerJump()
                        // The jump's own onPositionChanged must keep this adopted server time, not
                        // stamp `now` — else our own sync-move reads back next cycle as a newer local
                        // edit and bounces / pushes the audiobook back. Consumed by that emission.
                        position.setPendingServerJumpStamp(result.canonicalLastUpdate)
                        // The exact narrated sentence at the synced position, so a following "start
                        // readaloud" begins there (not the page top). The column-snap shifts the reader's
                        // progression off the synced point, so the planner's page check can't be relied on
                        // — start from the fragment directly instead. Only when not already narrating.
                        val syncedFragment = coordinator.fragmentForCanonical(json)
                        if (!playerCoordinator.state.value.isPlaying) readaloud.setPendingStartFragmentRef(syncedFragment)
                        persistReadaloudResumePosition(loc, fragmentRef = syncedFragment)
                    }
                }
                if (result.canonicalLastUpdate > localUpdatedAt) {
                    readingPositionStore.updateLocalTimestamp(sourceId, itemId, result.canonicalLastUpdate)
                }
            }
        }
    }

    /**
     * Delegates to [ReadaloudSession.flushReadaloudPositionToStores] (lifted in sub-task 8.2).
     * Kept as a private shim so the VM's existing call sites compile unchanged.
     */
    private suspend fun flushReadaloudPositionToStores(fragmentRef: String?) =
        readaloud.flushReadaloudPositionToStores(fragmentRef)

    /**
     * Delegates to [ReadaloudSession.mirrorReadingToAudiobook] (lifted in sub-task 8.2).
     * Kept as a private shim so the VM's existing call sites compile unchanged.
     * SAFEGUARD: the session implementation never adds startOffsetSec (audiobook double-count fix).
     */
    private suspend fun mirrorReadingToAudiobook(canonicalJson: String) =
        readaloud.mirrorReadingToAudiobook(canonicalJson)

    /**
     * Delegates to [ReadaloudSession.pushAudiobookFromReadingPosition] (lifted in sub-task 8.2).
     * Kept as a private shim so the VM's existing call sites compile unchanged.
     */
    private suspend fun pushAudiobookFromReadingPosition(fragment: String?) =
        readaloud.pushAudiobookFromReadingPosition(fragment)

    fun showFootnotePopup(content: FootnoteContent) {
        // Snapshot the user's reading position before the popup is mounted: this is the value we want
        // to restore them to after they tap an external URL inside the popup and return.
        footnotePopupOriginLocator = position.snapshotLastLocator()
        _footnotePopup.value = FootnotePopupState(content)
    }

    fun dismissFootnotePopup() {
        _footnotePopup.value = null
        // The origin is only meaningful while the popup is visible — drop it on every dismiss so a
        // later, unrelated backgrounding doesn't trigger the restore path.
        footnotePopupOriginLocator = null
    }

    /** Snapshot the popup's origin position before the URL tap launches the external browser. */
    fun captureFootnotePopupLinkOrigin() {
        position.forceSetReturnAnchor(footnotePopupOriginLocator ?: position.snapshotLastLocator())
    }

    // Return-to-position: when tapping an internal link (an in-document cross-reference like "Figure
    // 4.1", or a cross-chapter reference) lands the reader off the page it was on, we remember that
    // origin so a "Back" card can restore it. The behaviour (single-level, survives page turns, cleared
    // on return/dismiss) lives in [ReturnNavigator] so it's unit-testable; the VM only wires it up.
    private val returnNavigator = ReturnNavigator<Locator>()
    val returnTarget: StateFlow<Locator?> = returnNavigator.target
    val returnNavEvents: Flow<Locator> = returnNavigator.navEvents

    // The in-document cross-reference path (FootnoteAnchorBridge → ColumnSnap) calls this after it has
    // confirmed the snap actually moved the page off [origin].
    fun captureReturnTarget(origin: Locator) {
        returnNavigator.capture(origin)
    }

    // The cross-resource internal-link path (Readium's shouldFollowInternalLink). We drive the
    // navigation ourselves (so we can remember [origin]) instead of letting Readium follow it.
    fun followInternalLink(link: Link, origin: Locator) {
        returnNavigator.capture(origin)
        _navigationEvents.trySend(link)
    }

    // "Back" tapped — navigate to the captured origin and clear the card.
    fun returnToCapturedPosition() {
        returnNavigator.returnToOrigin()
    }

    // "✕" tapped — drop the card (and the captured origin) without navigating.
    fun dismissReturnTarget() {
        returnNavigator.dismiss()
    }

    fun onPositionChanged(locator: Locator) {
        // Delegate park-state clear to the session (lifted in sub-task 8.2).
        readaloud.onPositionBeforeForward(locator)
        // All hot-path position state is managed by PositionOrchestrator.
        val (spineHrefs, counts) = spinePositionCounts.value
        position.onPositionChanged(locator, spineHrefs = spineHrefs, spineCounts = counts)
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        lifecycle.resetCloseSync()
        position.resetInitialLocatorSeen()
        // Arm the speed-tracker baseline unconditionally — pre-refactor set sessionStart{Progression,Ms}
        // on every resume regardless of state, so a session that resumes while still loading and closes
        // before [ReaderState.Ready] still contributes its time delta. The heartbeat ticks here too;
        // they are safe no-ops until a locator exists ([syncCurrentPosition] early-returns on null).
        startReadingSession(initialTotalProgression = currentLocatorTotalProgression.value)
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            // Re-arm the per-book annotation live-pull loop alongside position sync.
            annotationSession.onReaderResumed()
        }
        // Restore the reading position after returning from background. Readium's WebView (all three
        // modes) consistently resets to the chapter top across the backgrounding round-trip —
        // originally observed via a FootnotePopup URL tap that backgrounded the activity for the
        // external browser, but the same applies to any home-button / app-switcher backgrounding.
        // [onReaderClosed] sets the return anchor via position.setReturnAnchor() on every ON_STOP so
        // this re-emit can fire for both paths. Leave the pending field populated (armReturnRestore
        // does this) and arm the returnRestoreAttempts watcher so [onPositionChanged] can re-fire if
        // Readium's column-snap clobbers our first attempt with a chapter-top emission.
        position.peekReturnAnchor()?.let { origin ->
            position.armReturnRestore(origin)
        }
    }

    fun onReaderClosed() {
        if (shouldRunReadingSideEffects(source)) {
            readingSessionCoordinator.onClosed(
                currentTotalProgression = currentLocatorTotalProgression.value,
                totalPositions = railSegments.value.fold(0f) { acc, seg -> acc + seg.weight },
            )
        }
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        readerStateHolder.isAudioPlaying = false
        // Cancel the Storyteller sync loop while the reader is backgrounded; reopening restarts it
        // when the readaloud player is re-opened. Owned by the session; cancel via method.
        readaloud.cancelStorytellerSync()
        // Cancel the annotation live-pull loop while the reader is backgrounded; onReaderResumed() restarts it.
        annotationSession.onReaderClosed()
        // Arm the resume-restore for the next ON_START. The footnote-popup URL-tap path may have
        // pre-armed this with the pre-popup origin (see captureFootnotePopupLinkOrigin); don't
        // overwrite that with the popup-nudged lastLocator. setReturnAnchor honours this contract.
        position.setReturnAnchor(position.snapshotLastLocator())
        if (!lifecycle.tryClaimCloseSync()) return
        // Leaving the book without first pressing X: persist the sentence narrating now so re-entry
        // resumes there. (Pressing X already persisted via closeReadaloud, which closes the player.)
        // Below the closeSyncDone guard so the ON_STOP + onDispose pair doesn't double-write.
        if (readaloud.readaloudOpen.value) {
            persistReadaloudResumePosition(position.snapshotLastLocator(), playerCoordinator.activeFragmentRef.value)
        }
        if (!shouldRunReadingSideEffects(source)) return
        val locator = position.snapshotLastLocator() ?: return
        // Stays on viewModelScope: runReaderSyncCycle mutates reader state (lastLocator,
        // pendingServerJumpStamp, …) and posts the inbound-jump channel, which must run on the main
        // thread while the screen is alive — it is not safe to relocate to a background flush scope.
        // The durable reading-position write survives a reopen/the offline sweep (ADR 0030).
        viewModelScope.launch {
            val payload = locator.toPayload()
            positionSaveCoordinator.onClose(payload.ebookProgress)
            if (lifecycle.matchedSync.value?.readerSync != null) runReaderSyncCycle(locator)
            else syncSession.sync(payload)
        }
    }

    fun onPanelStateChanged(isOpen: Boolean) {
        readerStateHolder.isPanelOpen = isOpen
    }

    // Translates via character-count CFIs; see EpubCfiTranslator and ADR 0013.
    private suspend fun Locator.toPayload(): SessionPayload = withContext(dispatchers.default) {
        val readingOrder = lifecycle.publication.value?.readingOrder ?: emptyList()
        val spineIndex = readingOrder.indexOfFirst { link ->
            normalizeEpubHref(link.href.toString()) == normalizeEpubHref(href.toString())
        }
        val fullCfi = if (spineIndex >= 0) {
            val spineStep = (spineIndex + 1) * 2
            val html = readChapterHtml(spineIndex)
            val docPath = html?.let { progressionToCfiDocPath(locations.progression ?: 0.0, it) }
            if (docPath != null) "epubcfi(/6/$spineStep!$docPath)"
            else buildEpubCfi(readingOrder, href)
        } else {
            buildEpubCfi(readingOrder, href)
        }
        SessionPayload(
            ebookLocation = fullCfi,
            ebookProgress = locations.totalProgression?.toFloat() ?: locations.progression?.toFloat() ?: 0f,
        )
    }

    /**
     * Resolves a raw ABS `epubcfi(...)` to a [Locator] using the open publication. Used by
     * [serverProgressToLocator] (live sync path: ABS always returns epubcfi) and by [openBook]
     * (legacy-row healing for DB rows written before the ADR 0030 translation fix). Returns null
     * when the string isn't an epubcfi or can't be resolved, letting callers fall back.
     */
    private suspend fun cfiStringToLocator(rawCfi: String): Locator? {
        val pub = lifecycle.publication.value ?: return null
        val cfi = rawCfi.takeIf { it.startsWith("epubcfi(") } ?: return null
        val spineIndex = epubCfiToSpineIndex(cfi)
        val link = spineIndex?.let { pub.readingOrder.getOrNull(it) }
        val html = spineIndex?.let { readChapterHtml(it) }
        val docPath = extractCfiDocPath(cfi)
        // A comma in the doc-path portion unambiguously marks a range CFI
        // (format: "docBase,startOffset,endOffset" after the resource assertion).
        // extractCfiDocPath strips the resource part first, so commas here are always range delimiters.
        val isRangeCfi = docPath != null && docPath.contains(',')
        val chapterProgression = when {
            html == null -> null
            isRangeCfi -> highlightStartProgression(cfi, html)
            docPath != null -> cfiDocPathToProgression(docPath, html)
            else -> null
        }
        if (link == null || chapterProgression == null) return null
        // For continuous-mode navigation: if the CFI references a named DOM element, store its
        // ID in locations.fragments so goToContinuous can anchor precisely on it rather than
        // relying on character-count progression (which doesn't account for pixel height variation).
        val anchorId = if (html != null && docPath != null) extractAnchorFromCfi(cfi, html) else null
        return try {
            Locator.fromJSON(
                JSONObject()
                    .put("href", link.href.toString())
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject()
                        .put("progression", chapterProgression)
                        .apply { if (anchorId != null) put("fragments", org.json.JSONArray().put(anchorId)) }
                    )
            )
        } catch (_: Exception) { null }
    }

    private suspend fun serverProgressToLocator(serverProgress: ServerProgress): Locator? {
        val pub = lifecycle.publication.value ?: return null
        cfiStringToLocator(serverProgress.ebookLocation)?.let { return it }
        // Fallback: no usable CFI — navigate via book-wide progress float
        val progress = serverProgress.ebookProgress.toDouble().coerceIn(0.0, 1.0)
        return if (progress > 0.0) pub.locateProgression(progress) else null
    }

    // ---- Annotations -------------------------------------------------------------------------

    // Set by the WebView-side body-font probe injected in [SELECTION_SPAN_TRACKER_JS] (issue
    // #484). Set-once — the first non-blank value the reader reports for the current book,
    // triggers a DB backfill of every legacy null-font row for the same book, and is cached so
    // subsequent chapter loads (which re-fire the tracker install) don't re-write the DB.
    private val bookBodyFontFamilyReported = java.util.concurrent.atomic.AtomicReference<String>("")

    // (Moved out of this block to above the init launch — see the fields near the constructor
    // parameters. Kotlin property initializers run in declaration order, and this declaration
    // used to sit below the init { viewModelScope.launch { openBook() } } block; when the
    // launched coroutine ran on Main.immediate it hit openBook's Highlights branch before this
    // field had been assigned, NPE-ing at `legacyImageUpgradeAttempted.compareAndSet(...)`.)

    /**
     * Called by [RiffleSelectionRectBridge.onBookBodyFont] on chapter install. Caches the value
     * on the VM (used as `bookBodyFontFamily` on subsequent elided-view opens) and, on the FIRST
     * non-blank report for the current book, backfills every legacy null-font row on this book
     * (issue #484). Only runs in FullBook mode — the elided view is a synthesised publication
     * whose body font is what we WROTE, not the origin's face.
     */
    fun noteBookBodyFontFamily(fontFamily: String) {
        if (source == ReaderSource.Highlights) return
        val trimmed = fontFamily.trim()
        if (trimmed.isBlank()) return
        // Set-once per (VM lifecycle, book). AtomicReference.compareAndSet returns false when
        // we've already stored a non-blank font — cheap no-op on the many repeat reports the
        // JS tracker fires as chapters install across a reading session.
        if (!bookBodyFontFamilyReported.compareAndSet("", trimmed)) return
        val sourceId = annotationServerId ?: return
        viewModelScope.launch {
            val updated = runCatching {
                annotationStore.backfillNullOriginFontFamily(sourceId, itemId, trimmed)
            }.getOrElse { 0 }
            if (updated > 0) {
                logger.d(LogChannel.HighlightMerge) {
                    "originFontFamily backfill sourceId=$sourceId itemId=$itemId font='$trimmed' updated=$updated"
                }
                scheduleAnnotationSync()
            }
        }
    }

    // Create a highlight at the current text selection in the user's last-used colour (see
    // [AnnotationSession.lastUsedHighlightColor]; falls back to yellow first-run). Anchors on a CFI range built from
    // the selection's start progression + selected text (ADR 0024), capturing the snippet + href.
    // Any existing highlights in the same chapter that overlap the new selection are deleted first —
    // a larger selection subsuming a previously highlighted word replaces that highlight.
    fun createHighlight(selectionLocator: Locator, anchorRect: IntRect) {
        if (source == ReaderSource.Highlights) return
        val sourceId = annotationServerId ?: return
        viewModelScope.launch {
            val pub = lifecycle.publication.value ?: return@launch
            val snippet = selectionLocator.text.highlight?.takeIf { it.isNotBlank() } ?: return@launch
            val href = selectionLocator.href.toString()
            val spineIndex = pub.readingOrder.indexOfFirst {
                normalizeEpubHref(it.href.toString()) == normalizeEpubHref(href)
            }
            if (spineIndex < 0) return@launch
            val spineStep = (spineIndex + 1) * 2
            val html = readChapterHtml(spineIndex) ?: return@launch
            val textBeforeCaptured = selectionLocator.text.before ?: ""
            // Recover the selection's true within-chapter position via anchored text-search.
            // Readium's `Locator.locations.progression` in paginated mode is the PAGE start, not
            // the selection start — every highlight created on the same page would otherwise
            // share the same stored progression, making the annotations-panel order (which sorts
            // by progression + createdAt) print same-page highlights in creation order instead of
            // reading order. See docs/superpowers/specs/2026-07-05-highlight-auto-merge-design.md.
            val totalChars = com.riffle.core.domain.countBodyChars(org.jsoup.Jsoup.parse(html).body())
            val locatedChar = if (totalChars > 0) {
                locateSnippetInBody(html, snippet, textBeforeCaptured)
            } else null
            val progression = if (locatedChar != null) {
                locatedChar.toDouble() / totalChars.toDouble()
            } else {
                selectionLocator.locations.progression ?: 0.0
            }

            // Delete existing highlights in the same chapter that the new selection covers.
            val newAfter = selectionLocator.text.after ?: ""
            annotationSession.highlightRenders.value
                .filter { normalizeEpubHref(it.locator.href.toString()) == normalizeEpubHref(href) }
                .forEach { render ->
                    val existSnippet = render.locator.text.highlight ?: return@forEach
                    val existAfter = render.locator.text.after ?: ""
                    if (highlightOverlapsAtSamePosition(snippet, newAfter, existSnippet, existAfter)) {
                        annotationStore.delete(render.id)
                    }
                }

            // NOTE: create-time auto-merge was removed after the 2026-07-05 initial rollout. It
            // surprised users who wanted to keep a fresh selection separate from a same-colour
            // neighbour (to recolour or note it differently). Merging now only fires at *edit*
            // time via [mergeAdjacentIntoHighlight] — recolour to match or note-cleared. See
            // docs/superpowers/specs/2026-07-05-highlight-auto-merge-design.md ("On note-clear").
            val cfiRange = if (locatedChar != null) {
                buildHighlightCfiRange(
                    spineStep, html, locatedChar, (locatedChar + snippet.length - 1L).coerceAtLeast(locatedChar),
                )
            } else {
                buildHighlightCfiRangeForSelection(spineStep, html, progression, snippet)
            } ?: return@launch
            // Figures enclosed by the highlight's range. Two independent sources:
            //   1. JS-side stash written by SELECTION_SPAN_TRACKER_JS on selectionchange
            //      (raster figures rasterised via canvas to a data URI, SVG serialised verbatim).
            //      When live, it carries `imageBytes` — best for the elided Highlights view.
            //   2. Kotlin-side walk of the same chapter HTML we already loaded to build the CFI
            //      range. Cannot rasterise (no canvas here), but gives us the enclosed figures'
            //      `href` reliably — the JS walker silently missed enclosed <img>s in paginated
            //      Readium, so this is the source of truth for whether the range crossed a figure.
            // Prefer stash entries for their `imageBytes`; supplement with the Kotlin walk so a
            // figure the JS walker missed still lands on the highlight (border-only, no bytes).
            val stashFigures = SelectionFiguresStash.consume()
            // Anchor the range to the snippet's actual position in the body text — progression is
            // too imprecise (off by 40-60 chars mid-chapter), which pushes the endpoint one char
            // short of an enclosed figure and misses it entirely. See anchorRangeToSnippet's KDoc.
            val (htmlStartChar, htmlEndChar) = anchorRangeToSnippet(
                html = html,
                snippet = snippet,
                textBefore = selectionLocator.text.before ?: "",
                progression = progression,
            )
            val htmlFigures = findEnclosedFiguresInHtml(html, htmlStartChar, htmlEndChar)
            // The Kotlin walker finds the href but has no way to rasterise (no canvas). Load the
            // image bytes straight from the Readium publication so the annotations list can render
            // a thumbnail — without bytes, rowKindFor drops back to the color-dot Highlight row and
            // the figure never surfaces visually in the list. Best-effort: any failure keeps the
            // walker entry as-is (border still draws in the reader; the list just shows the dot).
            // Image srcs in EPUB XHTML are relative to the chapter file, so we try both the raw href
            // and the chapter-directory-resolved form. The Publication resolves against its manifest
            // root, so a bare "image_rsrc2H6.jpg" from a chapter at "OEBPS/part0006.xhtml" needs to
            // become "OEBPS/image_rsrc2H6.jpg" before pub.get() will hand back the bytes.
            val chapterDir = href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val htmlFiguresWithBytes = htmlFigures.map { fig ->
                val figHref = fig.href
                if (fig.imageBytes != null || figHref == null) return@map fig
                val candidates = listOf(figHref, chapterDir + figHref).distinct()
                val bytes = candidates.firstNotNullOfOrNull { candidate ->
                    runCatching {
                        val url = org.readium.r2.shared.util.Url(candidate) ?: return@runCatching null
                        pub.get(url)?.read()?.getOrNull()
                    }.getOrNull()
                } ?: return@map fig
                val mime = when {
                    figHref.endsWith(".png", ignoreCase = true) -> "image/png"
                    figHref.endsWith(".gif", ignoreCase = true) -> "image/gif"
                    figHref.endsWith(".webp", ignoreCase = true) -> "image/webp"
                    else -> "image/jpeg"
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                fig.copy(imageBytes = "data:$mime;base64,$base64")
            }
            val embeddedFigures = mergeEnclosedFigures(stashFigures, htmlFiguresWithBytes)
            // Absorb standalone TYPE_IMAGE annotations that live at the same figure(s) this
            // highlight now encloses — the covering highlight subsumes them (same rule the
            // overlapping-highlight dedup above enforces for text, applied to figures). Their
            // imageBytes/note aren't ported forward: the highlight's own embeddedFigures carry the
            // figure, and once the highlight border shows, the standalone IMAGE annotation is
            // redundant UI.
            val absorbedFilenames = embeddedFigures.mapNotNull { it.href }.map(::figureHrefFilename).toSet()
            if (absorbedFilenames.isNotEmpty()) {
                annotationSession.annotations.value
                    .filter { it.type == com.riffle.core.database.AnnotationEntity.TYPE_IMAGE }
                    .filter { normalizeEpubHref(it.chapterHref) == normalizeEpubHref(href) }
                    .filter { it.imageHref?.let(::figureHrefFilename) in absorbedFilenames }
                    .forEach { annotationStore.delete(it.id) }
            }
            // Origin font-family at the selection start (issue #484). Non-blank contract on the
            // store — fall back to the store's plain-serif default when the WebView side happened
            // to return empty (rare selection-teardown race). The lazy backfill on chapter-load
            // rewrites approximated values as they get corrected against the real DOM.
            val originFont = SelectionFontStash.consume().takeIf { it.isNotBlank() } ?: FALLBACK_ORIGIN_FONT_FAMILY
            val created = annotationStore.createHighlight(
                sourceId = sourceId,
                itemId = itemId,
                cfi = cfiRange,
                textSnippet = snippet,
                chapterHref = href,
                textBefore = textBeforeCaptured,
                textAfter = newAfter,
                color = annotationSession.lastUsedHighlightColor.value.token,
                spineIndex = spineIndex,
                progression = progression,
                embeddedFigures = embeddedFigures,
                originFontFamily = originFont,
            )
            openHighlightActions(created.id, anchorRect)
            scheduleAnnotationSync()
            // observeHighlights re-emits → highlightRenders updates → the screen re-applies decorations.
        }
    }

    /**
     * Called by [AnnotationSession] after a recolour or note-clear that could newly make the row
     * merge-eligible with a same-chapter neighbour (spec: 2026-07-05-highlight-auto-merge-design.md).
     * Publication-dependent, so it lives here and is passed in as a factory param.
     *
     * [effectiveColor] and [effectiveNote] describe the post-mutation state so we don't race the
     * DAO Flow emit that refreshes [AnnotationSession.annotations].
     */
    private suspend fun mergeAdjacentIntoHighlight(
        id: String,
        effectiveColor: String,
        effectiveNote: String?,
    ) {
        logger.d(LogChannel.HighlightMerge) {
            "edit-merge entry id=$id color=$effectiveColor note=${effectiveNote?.let { "'${it.take(20)}'" }}"
        }
        if (!effectiveNote.isNullOrBlank()) {
            logger.d(LogChannel.HighlightMerge) { "edit-merge skip id=$id reason=note-still-present" }
            return
        }
        val sourceId = annotationServerId ?: run {
            logger.d(LogChannel.HighlightMerge) { "edit-merge skip id=$id reason=no-sourceId" }
            return
        }
        val current = annotationSession.annotations.value.firstOrNull { it.id == id }
        if (current == null) {
            logger.d(LogChannel.HighlightMerge) {
                "edit-merge skip id=$id reason=not-in-annotations poolSize=${annotationSession.annotations.value.size}"
            }
            return
        }
        if (current.type != AnnotationEntity.TYPE_HIGHLIGHT) {
            // Standalone TYPE_IMAGE (long-press-created) popups dismiss without any merge check
            // — a figure only becomes part of a highlight when the user's single selection
            // covers text on both sides of the figure at creation time. Symmetric on the text
            // side: [hasFigureInGap] rejects text-adjacency across a figure. (Revised 2026-07-10.)
            logger.d(LogChannel.HighlightMerge) { "edit-merge skip id=$id reason=type=${current.type}" }
            return
        }
        val pool = annotationSession.annotations.value
        // Trial-run the merge WITHOUT touching the DB. Track the leftmost source highlight as we
        // chain-absorb — its stored textBefore is our anchor for text-searching the actual char
        // position of the merged range in the chapter DOM. We can NOT use the stored progression
        // (or the CFI derived from it): Readium's `Locator.locations.progression` from a paginated
        // selection is the PAGE's progression, not the selection's, so every highlight on the same
        // page shares the same stored start position — useless for locating the merged range.
        var trialAnchor = current.toMergeAnchor().copy(color = effectiveColor, note = null)
        // Track leftmost + rightmost source highlights separately so we can text-search each
        // endpoint's position in the DOM. We can NOT compute `endChar = leftmost.start + composed.length`
        // because composed includes whitespace from Readium's textAfter (paragraph boundaries,
        // NBSP, etc.) that our readable-text char model does NOT count — the composed string is
        // longer than the actual DOM span for cross-paragraph merges.
        var leftmost: MergeAnchor = trialAnchor
        var rightmost: MergeAnchor = trialAnchor
        val toAbsorb = mutableListOf<Annotation>()
        val absorbedIds = mutableSetOf(id)
        logger.d(LogChannel.HighlightMerge) {
            "edit-merge anchor snippet='${trialAnchor.textSnippet.take(30)}' " +
                "before='${trialAnchor.textBefore.takeLast(30)}' after='${trialAnchor.textAfter.take(30)}' " +
                "poolSize=${pool.size}"
        }
        // Load the chapter HTML up front so figure-adjacency can inspect the DOM. Cross-figure
        // merges are FIRST-CLASS supported — a figure between two same-colour text highlights is
        // absorbed into the merged annotation, not rejected. See memory
        // `annotations-text-graph-symmetric`.
        val html = readChapterHtml(trialAnchor.spineIndex)
        if (html == null) {
            logger.d(LogChannel.HighlightMerge) { "edit-merge FAIL id=$id reason=no-html spine=${trialAnchor.spineIndex}" }
            return
        }
        while (true) {
            val match = findAnyMergeableNeighbor(
                trialAnchor,
                pool,
                excludeIds = absorbedIds,
                html = html,
            ) ?: break
            logger.d(LogChannel.HighlightMerge) {
                "edit-merge absorb neighborId=${match.neighbor.id} type=${match.neighbor.type} " +
                    "side=${match.side} neighborSnippet='${match.neighbor.textSnippet.take(30)}'"
            }
            when (match.side) {
                MergeSide.CANDIDATE_BEFORE_ANCHOR -> leftmost = match.neighbor.toMergeAnchor()
                MergeSide.CANDIDATE_AFTER_ANCHOR -> rightmost = match.neighbor.toMergeAnchor()
            }
            toAbsorb += match.neighbor
            absorbedIds += match.neighbor.id
            trialAnchor = applyMerge(trialAnchor, match)
        }
        if (toAbsorb.isEmpty()) {
            debugLogNoMergeReasons(trialAnchor, pool, id)
            return
        }
        val totalChars = com.riffle.core.domain.countBodyChars(org.jsoup.Jsoup.parse(html).body())
        if (totalChars <= 0) {
            logger.d(LogChannel.HighlightMerge) { "edit-merge FAIL id=$id reason=totalChars=0" }
            return
        }
        // Locate both endpoints of the merged range in the chapter's readable text via anchored
        // searches. Rightmost's endChar is *not* startChar + composed.length: composed carries
        // Readium's raw whitespace between snippets (newlines at paragraph boundaries, NBSP, etc.)
        // that our readable char model does not count.
        val startChar = locateSnippetInBody(html, leftmost.textSnippet, leftmost.textBefore)
        if (startChar == null) {
            logger.d(LogChannel.HighlightMerge) {
                "edit-merge FAIL id=$id reason=leftmost-not-locatable " +
                    "snippet='${leftmost.textSnippet.take(30)}' " +
                    "beforeTail='${leftmost.textBefore.takeLast(30)}'"
            }
            return
        }
        val rightmostStart = locateSnippetInBody(html, rightmost.textSnippet, rightmost.textBefore)
        if (rightmostStart == null) {
            logger.d(LogChannel.HighlightMerge) {
                "edit-merge FAIL id=$id reason=rightmost-not-locatable " +
                    "snippet='${rightmost.textSnippet.take(30)}' " +
                    "beforeTail='${rightmost.textBefore.takeLast(30)}'"
            }
            return
        }
        val endChar = (rightmostStart + rightmost.textSnippet.length).coerceAtMost(totalChars)
        if (endChar <= startChar) {
            logger.d(LogChannel.HighlightMerge) {
                "edit-merge FAIL id=$id reason=invalid-range startChar=$startChar endChar=$endChar"
            }
            return
        }
        val domSnippet = readableTextBetween(html, startChar, endChar)
        if (domSnippet.isNullOrEmpty()) {
            logger.d(LogChannel.HighlightMerge) { "edit-merge FAIL id=$id reason=dom-text-empty startChar=$startChar endChar=$endChar" }
            return
        }
        // Safety: our composed snippet and the DOM text must agree modulo whitespace normalisation.
        // If they diverge, the adjacency check false-matched (probably a suffix-of-context
        // coincidence) — abort rather than commit a broken merge.
        if (!snippetsAgreeIgnoringWhitespace(domSnippet, trialAnchor.textSnippet)) {
            logger.d(LogChannel.HighlightMerge) {
                "edit-merge FAIL id=$id reason=dom-mismatch " +
                    "dom='${domSnippet.take(60)}' composed='${trialAnchor.textSnippet.take(60)}'"
            }
            return
        }
        val spineStep = (trialAnchor.spineIndex + 1) * 2
        val cfiRange = buildHighlightCfiRange(spineStep, html, startChar, endChar - 1L)
        if (cfiRange == null) {
            logger.d(LogChannel.HighlightMerge) { "edit-merge FAIL id=$id reason=cfi-build-null" }
            return
        }
        val storedProgression = startChar.toDouble() / totalChars.toDouble()
        // Build the merged highlight's embeddedFigures by walking the merged range. Under the
        // revised merge rule (2026-07-10) the merged range can only contain figures that were
        // already inside one of the source highlights' individual ranges — [hasFigureInGap]
        // rejects any merge that would newly annotate a figure. Bytes/svg are inherited from
        // the anchor's own row if it carried them.
        val mergedEmbeddedFigures = buildMergedEmbeddedFigures(
            html = html,
            mergedStartChar = startChar,
            mergedEndChar = endChar,
            anchorId = id,
            pool = pool,
            absorbedHighlights = toAbsorb,
        )
        // All computations succeeded — commit: delete neighbours, then replace the anchor row.
        toAbsorb.forEach { annotationStore.delete(it.id) }
        annotationStore.delete(id)
        // The merged row inherits the anchor's origin font-family (issue #484); if the anchor was
        // a legacy row with none, fall back to the first non-null neighbour, then to the plain
        // serif default the elided view falls through to anyway.
        val anchorRow = pool.firstOrNull { it.id == id }
        val mergedOriginFont = anchorRow?.let { entityFontFamily(it) }
            ?: toAbsorb.firstNotNullOfOrNull { entityFontFamily(it) }
            ?: FALLBACK_ORIGIN_FONT_FAMILY
        val created = annotationStore.createHighlight(
            sourceId = sourceId,
            itemId = itemId,
            cfi = cfiRange,
            textSnippet = domSnippet,
            chapterHref = trialAnchor.chapterHref,
            textBefore = trialAnchor.textBefore,
            textAfter = trialAnchor.textAfter,
            color = trialAnchor.color,
            spineIndex = trialAnchor.spineIndex,
            progression = storedProgression,
            embeddedFigures = mergedEmbeddedFigures,
            originFontFamily = mergedOriginFont,
        )
        logger.d(LogChannel.HighlightMerge) {
            "edit-merge done anchorReplaced=$id newId=${created.id} absorbedText=${toAbsorb.size} " +
                "figures=${mergedEmbeddedFigures?.size ?: 0} " +
                "domLen=${domSnippet.length} startChar=$startChar"
        }
    }

    /**
     * Walk the merged range in the chapter DOM to compute embedded figures with position offsets
     * relative to the merged start. Under the revised merge rule (2026-07-10), the merged range
     * can only contain figures that were already inside one of the source highlights, because
     * [hasFigureInGap] blocks any merge whose gap would newly enclose a figure. Bytes/svg carried
     * on the pre-merge anchor row (if any) are correlated back onto the walked figures by
     * [figureHrefFilename] so a merge doesn't lose already-captured raster data.
     *
     * Returns null iff no figures — normalized to null on the persisted entity by AnnotationStore.
     */
    private fun buildMergedEmbeddedFigures(
        html: String,
        mergedStartChar: Long,
        mergedEndChar: Long,
        anchorId: String,
        pool: List<Annotation>,
        absorbedHighlights: List<Annotation> = emptyList(),
    ): List<com.riffle.core.domain.EmbeddedFigure>? {
        val walked = findEnclosedFiguresInHtml(html, mergedStartChar, mergedEndChar - 1L)
        if (walked.isEmpty()) return null
        // Index bytes/svg from every source row's embeddedFigures so a re-walk of the merged
        // range doesn't drop image data that was already captured at create time. Absorbed
        // neighbours count: an absorbed text-figure-text highlight carries the figure's imageBytes
        // even when the anchor row itself had none.
        val bytesByFilename = mutableMapOf<String, String>()
        val svgByFilename = mutableMapOf<String, String>()
        fun indexRow(ann: Annotation?) {
            ann?.embeddedFigures.orEmpty().forEach { fig ->
                val key = fig.href?.let(::figureHrefFilename) ?: return@forEach
                fig.imageBytes?.takeIf { it.isNotBlank() }?.let { bytesByFilename.putIfAbsent(key, it) }
                fig.svg?.takeIf { it.isNotBlank() }?.let { svgByFilename.putIfAbsent(key, it) }
            }
        }
        indexRow(pool.firstOrNull { it.id == anchorId })
        absorbedHighlights.forEach(::indexRow)
        return walked.mapIndexed { i, fig ->
            val key = fig.href?.let(::figureHrefFilename)
            fig.copy(
                order = i,
                imageBytes = fig.imageBytes ?: key?.let(bytesByFilename::get),
                svg = fig.svg ?: key?.let(svgByFilename::get),
            )
        }
    }

    /**
     * Content equality ignoring all whitespace. The composed snippet carries Readium's captured
     * whitespace between source snippets (single space, NBSP, `\n` at paragraph boundaries) but
     * the DOM extract's whitespace comes from the readable char model, which does not count
     * paragraph breaks as chars. Stripping whitespace on both sides compares just the letters —
     * enough to catch false-positive adjacency matches (extra CONTENT between the endpoints)
     * without tripping on the paragraph-break case.
     */
    private fun snippetsAgreeIgnoringWhitespace(a: String, b: String): Boolean {
        val na = a.filterNot { it.isWhitespace() }
        val nb = b.filterNot { it.isWhitespace() }
        return na.equals(nb, ignoreCase = true)
    }

    /** Per-neighbour reason line, so a "why no merge?" can be answered from a single logcat grep. */
    private fun debugLogNoMergeReasons(anchor: MergeAnchor, pool: List<Annotation>, anchorId: String) {
        val sameChapterOthers = pool.filter { it.id != anchorId && it.spineIndex == anchor.spineIndex }
        logger.d(LogChannel.HighlightMerge) {
            "edit-merge no-neighbor anchorId=$anchorId sameChapterOthers=${sameChapterOthers.size}"
        }
        for (n in sameChapterOthers) {
            val reason = when {
                n.type != AnnotationEntity.TYPE_HIGHLIGHT -> "type=${n.type}"
                !anchor.color.equals(n.color, ignoreCase = true) -> "color-mismatch anchor=${anchor.color} n=${n.color}"
                !n.note.isNullOrBlank() -> "neighbor-has-note"
                findAdjacency(anchor, n) == null -> "not-adjacent " +
                    "anchorAfterHead='${anchor.textAfter.take(40)}' " +
                    "anchorBeforeTail='${anchor.textBefore.takeLast(40)}' " +
                    "nSnippet='${n.textSnippet.take(30)}' " +
                    "nBeforeTail='${n.textBefore.takeLast(40)}' " +
                    "nAfterHead='${n.textAfter.take(40)}'"
                else -> "should-have-matched?"
            }
            logger.d(LogChannel.HighlightMerge) { "edit-merge candidate id=${n.id} reason=$reason" }
        }
    }

    /**
     * Toggle the bookmark for the reader's current page. If the page is already bookmarked (within
     * the page-aware eps [BookmarksController.bookmarkEpsFor] returns for the current chapter),
     * removes it; otherwise creates a new bookmark anchored to the top-of-viewport CFI with the
     * surrounding text as snippet.
     */
    fun toggleBookmark() {
        if (source == ReaderSource.Highlights) return
        val sourceId = annotationServerId ?: return
        viewModelScope.launch {
            val locator = position.snapshotLastLocator() ?: return@launch
            val href = locator.href.toString()
            val hrefNorm = normalizeEpubHref(href)
            val prog = locator.locations.progression ?: 0.0
            // Reuse the indicator's eps so "am I already bookmarked?" matches "is the indicator
            // lit?". A wider eps here would delete a nearby bookmark instead of creating a new
            // one on this specific page.
            val eps = bookmarks.bookmarkEpsFor(href)
            val existing = bookmarks.bookmarkPositions.value.firstOrNull { bm ->
                bm.chapterHref == hrefNorm && kotlin.math.abs(bm.progression - prog) <= eps
            }
            if (existing != null) {
                annotationStore.delete(existing.id)
                scheduleAnnotationSync()
            } else {
                val pub = lifecycle.publication.value ?: return@launch
                val spineIdx = pub.readingOrder.indexOfFirst { normalizeEpubHref(it.url().toString()) == normalizeEpubHref(href) }.coerceAtLeast(0)
                val totalProg = locator.locations.totalProgression
                val spineHrefs = pub.readingOrder.map { it.url().toString() }
                val segIdx = findActiveSegmentIndex(railSegments.value, href, spineHrefs)
                val chapterTitle = railSegments.value.getOrNull(segIdx)?.title?.takeIf { it.isNotBlank() }
                    ?.let { if (it.length > 30) it.take(30).trimEnd { c -> !c.isLetterOrDigit() } + "…" else it }
                val title = if (chapterTitle != null) {
                    val pct = (prog * 100).roundToInt().coerceIn(0, 100)
                    "$chapterTitle · $pct%"
                } else {
                    val pct = ((totalProg ?: prog) * 100).roundToInt().coerceIn(0, 100)
                    "${pct}%"
                }
                val cfi = locator.toPayload().ebookLocation
                val snippet = locator.text.before?.take(200).orEmpty()
                // Bookmarks have no live text selection to read a range font from — fall back to
                // the last captured selection font when one is pending, else the plain-serif
                // placeholder (issue #484). Backfill will refine legacy rows on chapter-load.
                val bookmarkFont = SelectionFontStash.consume().takeIf { it.isNotBlank() }
                    ?: FALLBACK_ORIGIN_FONT_FAMILY
                annotationStore.createBookmark(
                    sourceId = sourceId,
                    itemId = itemId,
                    cfi = cfi,
                    textSnippet = snippet,
                    chapterHref = href,
                    spineIndex = spineIdx,
                    progression = prog,
                    bookmarkTitle = title,
                    originFontFamily = bookmarkFont,
                )
                scheduleAnnotationSync()
            }
        }
    }

    /**
     * Handle a figure long-press from either paged/vertical's [FigureTapBridge] or continuous's
     * [ContinuousReaderView.onFigureLongPress] — both dispatch the same parsed
     * [FigureLongPressPayload] via the callback threaded through `EpubReaderScreen.kt`, along with
     * [anchorRect] (the figure's on-screen rect, already translated from the payload's CSS-px rect
     * by [ChapterWebViewBinder] / the paged DisposableEffect in `EpubReaderScreen.kt`).
     *
     * First looks up whether the figure already has a live `TYPE_IMAGE` annotation in the current
     * chapter via [AnnotationStore.findImageAnnotationForFigure]. If so, this is a re-long-press on
     * an already-annotated figure: skip creating a duplicate and open [openHighlightActions] on the
     * existing annotation so the user can edit it. Otherwise, creates a new `TYPE_IMAGE` annotation
     * anchored to the current reading position, mirroring [toggleBookmark]'s point-CFI machinery (a
     * figure long-press has no text selection to build a range from), then opens the same popup on
     * the freshly created annotation — matching [createHighlight]'s create-then-open-popup pattern.
     *
     * Exactly one of [FigureLongPressPayload.href] / [FigureLongPressPayload.svg] is non-null —
     * `href` for `img`/`picture` targets, `svg` for inline `<svg>` targets — and that split is
     * threaded straight through to both the lookup and [AnnotationStore.createImageAnnotation]'s
     * imageHref/imageSvg.
     */
    internal suspend fun onFigureLongPress(payload: FigureLongPressPayload, anchorRect: IntRect) {
        if (source == ReaderSource.Highlights) return
        val sourceId = annotationServerId ?: return
        val pub = lifecycle.publication.value ?: return
        val locator = position.snapshotLastLocator() ?: return
        val href = locator.href.toString()

        val existing = annotationStore.findImageAnnotationForFigure(
            sourceId = sourceId,
            itemId = itemId,
            chapterHref = href,
            imageHref = payload.href,
            imageSvg = payload.svg,
        )
        if (existing != null) {
            openHighlightActions(existing.id, anchorRect)
            return
        }

        // Also check for a HIGHLIGHT whose embeddedFigures cover this figure — the text
        // highlight and its enclosed figure are ONE annotation; long-pressing the outlined
        // figure should route to the same edit popup, so a subsequent delete removes both the
        // text highlight AND the figure's border together. Match by filename (raster) or by
        // SVG-prefix (inline SVG) — mirrors FigureBorderDecoration's newest-wins matcher.
        val payloadFilename = payload.href?.let(::figureHrefFilename)
        val payloadSvgPrefix = payload.svg?.take(200)
        val enclosingHighlight = annotationSession.annotations.value.firstOrNull { a ->
            a.type == com.riffle.core.database.AnnotationEntity.TYPE_HIGHLIGHT &&
                normalizeEpubHref(a.chapterHref) == normalizeEpubHref(href) &&
                a.embeddedFigures.orEmpty().any { fig ->
                    val figHref = fig.href
                    val figSvg = fig.svg
                    when {
                        payloadFilename != null && figHref != null ->
                            figureHrefFilename(figHref) == payloadFilename
                        payloadSvgPrefix != null && figSvg != null ->
                            figSvg.take(200) == payloadSvgPrefix
                        else -> false
                    }
                }
        }
        if (enclosingHighlight != null) {
            openHighlightActions(enclosingHighlight.id, anchorRect)
            return
        }

        val spineIndex = pub.readingOrder.indexOfFirst {
            normalizeEpubHref(it.href.toString()) == normalizeEpubHref(href)
        }.coerceAtLeast(0)
        val progression = locator.locations.progression ?: 0.0
        val cfi = locator.toPayload().ebookLocation

        // Caption-highlight path: when the JS long-press payload resolved the figure's caption to
        // a real DOM element (figcaption or a nearby "Figure N…" block), persist the figure as a
        // TYPE_HIGHLIGHT that covers the caption text, with the figure carried as an
        // embeddedFigure. This makes the caption a first-class annotated span — tap/select over
        // the caption routes to this annotation, and a fresh highlight can't land on top of it.
        // Falls back to the TYPE_IMAGE path below if the caption can't be located Kotlin-side
        // (e.g. the JS payload had captionRange but jsoup couldn't disambiguate two identical
        // captions in the same chapter).
        val captionRange = payload.captionRange
        if (captionRange != null) {
            val spineStep = (spineIndex + 1) * 2
            val html = readChapterHtml(spineIndex)
            val startChar = html?.let { locateSnippetInBody(it, captionRange.text, captionRange.textBefore) }
            val cfiRange = if (html != null && startChar != null) {
                buildHighlightCfiRange(
                    spineStep = spineStep,
                    html = html,
                    startChar = startChar,
                    endChar = (startChar + captionRange.text.length - 1L).coerceAtLeast(startChar),
                )
            } else null
            if (cfiRange != null) {
                // caption="" — the highlight's textSnippet already carries the caption text, and
                // the elided-view renderer would otherwise emit both the figure's own <figcaption>
                // and an outer <p> for the same string (the "text doubled under each graph" bug
                // observed on 2026-07-14). Leaving the field empty keeps the DB clean for new
                // caption-highlights; existing rows are handled render-side.
                val figure = com.riffle.core.domain.EmbeddedFigure(
                    href = payload.href,
                    svg = payload.svg,
                    caption = "",
                    order = 0,
                    imageBytes = payload.imageBytes,
                    charOffset = 0L,
                )
                // Dedup: a same-chapter HIGHLIGHT already covering the caption text (by CFI
                // equality or normalized-textSnippet equality) absorbs the new figure into its
                // `embeddedFigures` instead of stacking a duplicate row. Catches both (a) a
                // re-long-press of the same figure whose first attempt landed as a bare-text
                // caption HIGHLIGHT with empty embeddedFigures — the "1.5s duplicate" the user
                // reproduced on 2026-07-14 — and (b) a pre-existing text-selection highlight of
                // the caption that should upgrade in place instead of getting shadowed.
                val normalizedCaption = normalizeCaptionText(captionRange.text)
                val existingRows = runCatching {
                    annotationDao.getForItem(sourceId, itemId)
                }.getOrDefault(emptyList())
                val existingHighlight = existingRows.firstOrNull { row ->
                    row.type == com.riffle.core.database.AnnotationEntity.TYPE_HIGHLIGHT &&
                        normalizeEpubHref(row.chapterHref) == normalizeEpubHref(href) &&
                        (row.cfi == cfiRange ||
                            normalizeCaptionText(row.textSnippet) == normalizedCaption)
                }
                if (existingHighlight != null) {
                    annotationStore.mergeFiguresIntoHighlight(existingHighlight.id, listOf(figure))
                    openHighlightActions(existingHighlight.id, anchorRect)
                    scheduleAnnotationSync()
                    return
                }
                val created = annotationStore.createHighlight(
                    sourceId = sourceId,
                    itemId = itemId,
                    cfi = cfiRange,
                    textSnippet = captionRange.text,
                    chapterHref = href,
                    textBefore = captionRange.textBefore,
                    textAfter = captionRange.textAfter,
                    color = annotationSession.lastUsedHighlightColor.value.token,
                    spineIndex = spineIndex,
                    progression = progression,
                    embeddedFigures = listOf(figure),
                    originFontFamily = FALLBACK_ORIGIN_FONT_FAMILY,
                )
                openHighlightActions(created.id, anchorRect)
                scheduleAnnotationSync()
                return
            }
        }

        // No visible caption (alt/aria-label-only, or bare image with no nearby caption block):
        // create a TYPE_IMAGE annotation as before. These carry no persisted text range, so their
        // figcaption tint (if any) remains the render-side CSS pass in FigureBorderInjection.
        val created = annotationStore.createImageAnnotation(
            sourceId = sourceId,
            itemId = itemId,
            cfi = cfi,
            textSnippet = payload.caption,
            chapterHref = href,
            spineIndex = spineIndex,
            progression = progression,
            imageHref = payload.href,
            imageSvg = payload.svg,
            imageBytes = payload.imageBytes,
            color = annotationSession.lastUsedHighlightColor.value.token,
        )
        openHighlightActions(created.id, anchorRect)
        scheduleAnnotationSync()
    }

    /** Recolour an existing highlight; annotationStore re-emits → decoration re-applies.
     *  Highlights mode: the observer diffs the store re-emit and dispatches a targeted DOM
     *  patch via [highlightDomPatches], so the accent bar refreshes in place — no rebuild. */
    fun recolorHighlight(id: String, color: HighlightColor) {
        viewModelScope.launch {
            annotationSession.recolorHighlight(id, color)
        }
    }

    /**
     * Reload the Highlights-mode reader so the synthesised chapters re-render against the
     * updated annotation store (colour, note text, deletions). A back-to-back
     * `Loading→Ready(newPub)` set through StateFlow can be coalesced — the collector may only
     * see the last value and never leave composition, so the new Publication is handed to the
     * SAME Readium fragment instance and its diff-check treats it as a no-op. The delay yields
     * one frame so Compose observes the Loading state and unmounts before the fresh Ready
     * arrives.
     */
    private suspend fun reloadHighlightsView() {
        _state.value = ReaderState.Loading
        kotlinx.coroutines.delay(16)
        openBook()
    }

    /**
     * Subscribe (once) to the annotation store for this book. Each emission is diffed
     * per-annotation against [highlightsRenderedById]:
     *  - **Colour edited** ⇒ emit [HighlightsDomPatch.Recolor] + rewrite the chapter's bytes.
     *  - **Note edited** ⇒ emit [HighlightsDomPatch.SetNote] + rewrite the chapter's bytes.
     *  - **Removed (deleted OR missing from the new set)** ⇒ if the highlight was the LAST one
     *    on its chapter, the spine shrinks → fall back to [reloadHighlightsView] (or to the
     *    empty-book close event via [reloadOrCloseHighlightsAfterDelete]); otherwise emit
     *    [HighlightsDomPatch.Remove] + rewrite bytes.
     *  - **Added and its chapter is already in the current spine** ⇒ TODO — for now fall back
     *    to [reloadHighlightsView] so the insert lands in CFI order without complex DOM math.
     *    Adds are the least-common case (sync inserts) and the rebuild is only visible for
     *    that one event.
     *  - **Added into a brand-new chapter** ⇒ [reloadHighlightsView] (structural change).
     * The by-id snapshot is refreshed at the end of every dispatch so the next emission's diff
     * is against what's actually on screen — this suppresses the redundant echo emission that
     * every local write triggers via Room's Flow.
     */
    private fun ensureHighlightsObserver(sourceId: String, itemId: String) {
        if (highlightsObserverJob?.isActive == true) return
        highlightsObserverJob = viewModelScope.launch {
            annotationDao.observeAnnotationsByPosition(sourceId, itemId).collect { annotations ->
                // Filter to highlights only — the elided reader's spine, chapter HTML, and every
                // downstream patch/rewrite is highlights-only. If we left bookmarks in this stream:
                //  (1) a chapter's byte rewrite would render bookmarks as fake highlight paragraphs
                //      (renderChapterHtml paints every row as an accent-bar <p>);
                //  (2) the empty-set close check would fire late when a chapter had highlights
                //      deleted but a bookmark remained, blocking [reloadOrCloseHighlightsAfterDelete];
                //  (3) a bookmark ADD would count as a structural change and re-introduce the
                //      full-rebuild flash the DOM-patch pipeline was written to eliminate.
                val incomingById = annotations
                    .asSequence()
                    .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT }
                    .associateBy { it.id }
                if (sameById(incomingById, highlightsRenderedById)) return@collect

                val handle = highlightsPublicationHandle
                val previous = highlightsRenderedById

                // Empty set — close the reader; the elided Publication would need an empty spine
                // and Readium crashes on that.
                if (incomingById.isEmpty()) {
                    if (reloadOrCloseHighlightsAfterDelete()) return@collect
                    reloadHighlightsView(); return@collect
                }

                val removedIds = previous.keys - incomingById.keys
                val addedIds = incomingById.keys - previous.keys
                val changedIds = incomingById.keys.intersect(previous.keys).filter { id ->
                    val prev = previous.getValue(id)
                    val next = incomingById.getValue(id)
                    prev.color != next.color ||
                        prev.note != next.note ||
                        prev.chapterHref != next.chapterHref
                }

                // Structural changes → full rebuild.
                //   * Any add whose chapterHref isn't already in the spine (new chapter needs adding).
                //   * Any add at all (in-chapter insert not yet implemented — falls back to rebuild).
                //   * Any remove that empties its chapter.
                //   * Any change that moved a highlight between chapters (rare — sync-only reorder).
                val structural = addedIds.isNotEmpty() ||
                    removedIds.any { id ->
                        val chapterHref = previous.getValue(id).chapterHref
                        val livePeersInChapter = incomingById.values.count { it.chapterHref == chapterHref }
                        livePeersInChapter == 0
                    } ||
                    changedIds.any { id ->
                        previous.getValue(id).chapterHref != incomingById.getValue(id).chapterHref
                    }
                if (structural || handle == null) {
                    if (reloadOrCloseHighlightsAfterDelete()) return@collect
                    reloadHighlightsView()
                    return@collect
                }

                // Partial path — recolour / note / delete on chapters that already exist.
                val touchedChapters = mutableSetOf<String>()
                for (id in removedIds) {
                    _highlightDomPatches.tryEmit(
                        com.riffle.app.feature.reader.highlights.HighlightsDomPatch.Remove(id),
                    )
                    previous[id]?.chapterHref?.let(touchedChapters::add)
                }
                for (id in changedIds) {
                    val next = incomingById.getValue(id)
                    val previousRow = previous.getValue(id)
                    val accent = highlightAccentCssRgba(next.color)
                    if (previousRow.color != next.color) {
                        _highlightDomPatches.tryEmit(
                            com.riffle.app.feature.reader.highlights.HighlightsDomPatch.Recolor(id, accent),
                        )
                    }
                    if (previousRow.note != next.note) {
                        _highlightDomPatches.tryEmit(
                            com.riffle.app.feature.reader.highlights.HighlightsDomPatch
                                .SetNote(id, accent, next.note),
                        )
                    }
                    touchedChapters += next.chapterHref
                }

                // Rewrite bytes for every touched chapter so a subsequent chapter-back
                // navigation surfaces the updated HTML instead of the pre-edit baked snapshot.
                if (touchedChapters.isNotEmpty()) {
                    val incomingByChapter = incomingById.values.groupBy { it.chapterHref }
                    // Chapter titles were derived at open-time from the TOC — reuse them.
                    val titleByHref =
                        highlightsResumeChapters?.associate { it.href to it.title } ?: emptyMap()
                    for (chapterHref in touchedChapters) {
                        val livePeers = incomingByChapter[chapterHref].orEmpty()
                            .sortedWith(compareBy({ it.spineIndex }, { it.progression }, { it.createdAt }, { it.id }))
                        if (livePeers.isEmpty()) continue // Empty chapter would be structural — handled above.
                        val chapter = ChapterElision(
                            href = chapterHref,
                            title = titleByHref[chapterHref] ?: chapterHref,
                            highlights = livePeers,
                        )
                        val freshHtml = highlightsPublicationFactory.renderChapterHtml(
                            chapter = chapter,
                            bookBodyFontFamily = elidedBodyFontFamily,
                            dataUriByHref = highlightsPublicationHandle?.figureBytesByHref.orEmpty(),
                        )
                        handle.setChapterBytes(chapterHref, freshHtml)
                    }
                }

                // Refresh the by-id snapshot to what's now on screen. Without this the NEXT
                // emission would re-observe the same "changed" annotation and re-emit the same
                // patch on every Room re-emit.
                highlightsRenderedById = incomingById
            }
        }
    }

    /** Compare two id-keyed AnnotationEntity maps by the fields the elided reader actually
     *  renders (colour, note, chapterHref, position within chapter). A pure-equality check on
     *  the full entity would over-trigger on unrelated bumps to `updatedAt` / `lastSyncedAt` /
     *  provenance fields — those get rewritten on every sync round-trip and would cause the
     *  observer to think "changed" every time. */
    private fun sameById(a: Map<String, AnnotationEntity>, b: Map<String, AnnotationEntity>): Boolean {
        if (a.size != b.size) return false
        for ((id, aRow) in a) {
            val bRow = b[id] ?: return false
            if (aRow.color != bRow.color) return false
            if (aRow.note != bRow.note) return false
            if (aRow.chapterHref != bRow.chapterHref) return false
        }
        return true
    }

    /** Palette CSS the elided reader paints an accent bar / note border with. Kept identical to
     *  what [HighlightsPublicationFactory.renderChapterHtml] bakes into the initial HTML so a
     *  live recolour lands on the SAME colour a fresh page load would produce. */
    private fun highlightAccentCssRgba(colorToken: String): String =
        com.riffle.core.domain.HighlightColor.fromToken(colorToken).argb.toCssRgba()

    /** Soft-delete a highlight; annotationStore re-emits without it → decoration is removed.
     *  Highlights mode: the observer sees the id disappear and dispatches a Remove DOM patch
     *  (or, if the chapter empties, falls back to reloadOrCloseHighlightsAfterDelete). */
    fun deleteHighlight(id: String) {
        viewModelScope.launch {
            annotationSession.deleteHighlight(id)
        }
    }

    /** Navigate the reader to the annotation with [id], then close the annotations panel. */
    fun navigateToAnnotation(id: String) = annotationSession.navigateToAnnotation(id)

    /** Rename a bookmark; delegates to BookmarksController which calls scheduleAnnotationSync. */
    fun renameBookmark(id: String, title: String) = bookmarks.renameBookmark(id, title)

    /** Soft-delete any annotation (highlight or bookmark); clears highlight-edit state if needed.
     *  Highlights mode: observer takes over as with [deleteHighlight]. */
    fun deleteAnnotation(id: String) {
        viewModelScope.launch {
            annotationSession.deleteAnnotation(id)
        }
    }

    /**
     * After a delete in Highlights mode: if the book has no live highlights left, emit
     * [ReaderNavEvent.CloseEmptyHighlights] so the nav host can pop this reader off the back stack,
     * and return `true` — the synthesised Publication would otherwise be built with an empty
     * readingOrder and Readium's navigator crashes on that. Returns `false` when at least one
     * highlight remains, meaning the caller should proceed with the normal reload.
     */
    private suspend fun reloadOrCloseHighlightsAfterDelete(): Boolean {
        val sourceId = annotationServerId ?: return false
        val rows = annotationDao.getForItem(sourceId, itemId)
        if (!highlightsShouldCloseAfterDelete(rows)) return false
        _readerNavEvents.send(ReaderNavEvent.CloseEmptyHighlights)
        return true
    }

    /** Save (or clear) the note on a highlight; blank text is treated as null (removes the note).
     *  Highlights mode: observer emits a SetNote DOM patch (add / update / remove aside). */
    fun updateHighlightNote(id: String, note: String?) {
        viewModelScope.launch {
            annotationSession.updateHighlightNote(id, note)
        }
    }

    /**
     * Highlights mode only (Task 9, ADR 0041): tapping "Open in book" on a highlight in the
     * synthesised, elided reader navigates OUT to the full-book reader at that highlight's CFI.
     * Falls back to the active server if [annotationServerId] hasn't resolved yet — a race that
     * shouldn't occur in practice, since Highlights mode had to load highlights (and thus resolve
     * a server) before any highlight could be tapped.
     */
    fun openHighlightInSourceBook(annotationId: String) {
        viewModelScope.launch {
            val row = annotationDao.getById(annotationId) ?: return@launch
            _readerNavEvents.send(
                ReaderNavEvent.OpenInSourceBook(
                    sourceId = row.sourceId,
                    itemId = row.itemId,
                    cfi = row.cfi,
                ),
            )
        }
    }

    /** Debounced push of the local non-deleted annotations to the WebDAV target (#76). No-op when
     *  sync is not configured or the ABS namespace hasn't been resolved (Storyteller-only, offline
     *  backfill failure). [annotationServerId] and [annotationNamespace] are set together on book
     *  open, so checking the namespace also guarantees the sourceId is present.
     *
     *  Still needed by createHighlight and toggleBookmark which are publication-dependent and stay in VM. */
    private fun scheduleAnnotationSync() {
        val sid = annotationServerId ?: return
        val ns = annotationNamespace ?: return
        annotationSyncController.scheduleDebounce(sid, ns, itemId)
    }

    // Reconstruct a persisted highlight into a renderable Readium locator. The CFI start re-anchors
    // the within-chapter position; the text snippet lets Readium's decorator locate the range.
    // Called as the highlightRenderResolver injected into annotationSession.bind().
    /**
     * Emit one HighlightRender per contiguous text segment of the annotation. For a highlight
     * that doesn't cross a figure this is a single render (unchanged behaviour). For a highlight
     * whose snippet crosses one or more figures — captured via each figure's `charOffset` — this
     * emits N+1 renders, one per text segment either side of the figures. Each segment carries
     * its OWN snippet (a run of contiguous text that actually exists in the DOM) so Readium's
     * decoration matcher can locate + paint it; the segments share the same base annotation id
     * with a `#segN` suffix so `annotationIdOf` can strip it back at tap-dispatch time.
     */
    private suspend fun annotationToRender(a: Annotation): List<HighlightRender> {
        val pub = lifecycle.publication.value ?: return emptyList()
        val spineIndex = epubCfiToSpineIndex(a.cfi) ?: return emptyList()
        val link = pub.readingOrder.getOrNull(spineIndex) ?: return emptyList()
        val html = readChapterHtml(spineIndex) ?: return emptyList()
        val progression = highlightStartProgression(a.cfi, html) ?: return emptyList()
        val href = link.href.toString()
        // Split points inside the snippet where a figure sits. Legacy rows without offsets → one
        // segment covering the whole snippet (v1 behaviour).
        val offsets = a.embeddedFigures
            ?.sortedBy { it.order }
            ?.mapNotNull { it.charOffset }
            .orEmpty()
        val segments: List<String> = if (offsets.isEmpty()) {
            listOf(a.textSnippet)
        } else {
            splitSnippetForFiguresAt(a.textSnippet, offsets)
        }
        return segments
            .mapIndexedNotNull { index, segmentText ->
                if (segmentText.isBlank()) return@mapIndexedNotNull null
                val locator = try {
                    Locator.fromJSON(
                        JSONObject()
                            .put("href", href)
                            .put("type", "application/xhtml+xml")
                            .put("locations", JSONObject().put("progression", progression))
                            .put("text", JSONObject()
                                .put("before", if (index == 0) a.textBefore else "")
                                .put("highlight", segmentText)
                                .put("after", if (index == segments.lastIndex) a.textAfter else "")),
                    )
                } catch (_: Exception) {
                    null
                } ?: return@mapIndexedNotNull null
                val decorationId = if (segments.size == 1) a.id else "${a.id}#seg$index"
                HighlightRender(decorationId, locator, a.color, a.note)
            }
    }

    private suspend fun readChapterHtml(spineIndex: Int): String? {
        chapterHtmlCache[spineIndex]?.let { return it }
        return withContext(dispatchers.io) {
            val pub = lifecycle.publication.value ?: return@withContext null
            val link = pub.readingOrder.getOrNull(spineIndex) ?: return@withContext null
            val entryPath = normalizeEpubHref(link.href.toString())
            val zip = lifecycle.zip() ?: return@withContext null
            try {
                zip.getEntry(entryPath)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().readText()
                }?.also { chapterHtmlCache[spineIndex] = it }
            } catch (_: Exception) { null }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop Cadence. CadenceController is @Singleton so it survives this VM; without this
        // unbind, a running ticker would keep advancing past book-close and the SAME session
        // would resume when the user reopens the book. Also drops the merged quotes/hrefs
        // accumulator held by the source so the next book starts fresh.
        cadenceController.unbind()
        _cadenceQuotes.value = emptyMap()
        _cadenceChapterHrefs = emptyMap()
        // Stop any in-flight Auto-Scroll so the next book open starts idle, not auto-scrolling
        // mid-session into someone else's text.
        formatting.onBookClosed()
        // Push any pending annotation edits to the WebDAV sync target (#76). Delegated to
        // annotationSession which uses progressFlushScope so the PATCH survives viewModelScope
        // cancellation at teardown.
        annotationSession.onBookClosed()
        // Tear down all readaloud session background jobs (pre-warm, sidecar obs, sync, speed debounce).
        readaloud.onBookClosed()
        // Release openReconcile claims, close the zip and publication.
        lifecycle.onCleared()
        // Tear down the audio session so it doesn't outlive the reader (clears the highlight too).
        playerCoordinator.close()
        // Readaloud can't outlive the reader, so this session is no longer playing.
        nowPlayingStore.clearIf { it is com.riffle.app.playback.NowPlaying.Readaloud && it.itemId == itemId }
        // Cancel the coordinator's state-collection scope so it isn't leaked past this ViewModel.
        playerCoordinator.dispose()
    }

    /**
     * True when one of this item's bookmarks falls on the reader's current page.
     * Delegated to [BookmarksController] which does the reactive combination.
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> = bookmarks.isCurrentPageBookmarked

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible

    fun openToc() {
        annotationSession.closeAnnotationsPanel()
        _tocVisible.value = true
    }
    fun closeToc() { _tocVisible.value = false }

    val annotationsPanelVisible: StateFlow<Boolean> = annotationSession.annotationsPanelVisible

    val annotations: StateFlow<List<com.riffle.core.domain.Annotation>> = annotationSession.annotations

    val annotationNavigationEvents: Flow<AnnotationSession.AnnotationNavigationEvent> = annotationSession.annotationNavigationEvents

    val syncBanner = annotationSession.syncBanner

    fun openAnnotationsPanel() {
        _tocVisible.value = false
        annotationSession.openAnnotationsPanel()
    }

    fun closeAnnotationsPanel() = annotationSession.closeAnnotationsPanel()

    val tocEntries: StateFlow<List<TocEntry>> = state
        .map { (it as? ReaderState.Ready)?.publication?.tableOfContents?.toTocEntries() ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Per-spine-resource position counts (Readium-computed), paired with their spine hrefs.
     * Drives book-wide totalProgression in continuous mode (see [computeTotalProgression]) and
     * feeds [railSegments] weighting. Exposed so the continuous reader can build correctly
     * scaled locators when a rail segment spans multiple spine resources.
     */
    private val _viewportFractionByHref = MutableStateFlow<Map<String, Double>>(emptyMap())

    /**
     * Live per-chapter `viewportSize / chapterSize` fractions. Populated by [putViewportFraction]
     * from `ReaderPresenter.viewportFractionEvents` in `EpubReaderScreen`, and read by
     * `BookmarksController.bookmarkEpsFor` as the geometrically-correct half-viewport bound
     * for the bookmark indicator (issue #399). Only changes when a chapter's measurement
     * changes — never on scroll.
     */
    val viewportFractionByHref: StateFlow<Map<String, Double>> = _viewportFractionByHref

    /**
     * Record a fresh viewport-fraction measurement for [href]. No-ops when the incoming value
     * equals the currently stored one — this per-entry distinct-until-changed guard is what
     * lets the bookmark combine avoid the scroll-driven recomposition churn that flaked
     * `HighlightRepaintOrientationHarnessTest` in the prior attempt (see issue #399).
     */
    fun putViewportFraction(href: String, fraction: Double) {
        if (href.isEmpty() || fraction <= 0.0 || !fraction.isFinite()) return
        val current = _viewportFractionByHref.value
        if (current[href] == fraction) return
        _viewportFractionByHref.value = current + (href to fraction)
    }

    val spinePositionCounts: StateFlow<Pair<List<String>, List<Int>>> = state
        .map { s ->
            val ready = s as? ReaderState.Ready ?: return@map emptyList<String>() to emptyList<Int>()
            val pub = ready.publication
            val positions: List<List<Locator>> = try {
                pub.positionsByReadingOrder()
            } catch (_: Throwable) {
                emptyList()
            }
            val spineHrefs = pub.readingOrder.map { it.url().toString() }
            val counts = positions.map { it.size }
            spineHrefs to counts
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<String>() to emptyList<Int>())

    val railSegments: StateFlow<List<RailSegment>> = combine(state, spinePositionCounts) { s, (spineHrefs, counts) ->
        val ready = s as? ReaderState.Ready ?: return@combine emptyList()
        val base = buildRailSegments(ready.publication.tableOfContents.toTocEntries(), ready.title)
        weightSegmentsByChapterLength(base, spineHrefs, counts)
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeRailSegmentIndex: StateFlow<Int> = combine(
        railSegments,
        currentLocatorHref,
        state,
    ) { segments, href, s ->
        if (href == null) return@combine 0
        val spineHrefs = (s as? ReaderState.Ready)?.publication?.readingOrder
            ?.map { it.url().toString() }
            .orEmpty()
        findActiveSegmentIndex(segments, href, spineHrefs)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Cursor position within the rail (0..1). Driven by totalProgression (monotonically
    // increasing across the whole book) rather than chapterProgression (resets to 0 at each
    // new spine resource). Using chapterProgression caused the cursor to jump backward every
    // time a new spine resource loaded within the same segment (e.g. reading SOL 376 → SOL 380
    // inside a single "Chapter 20" segment). totalProgression is converted to a within-segment
    // fraction so the cursor stays inside the active segment's bounds.
    val railCursorPosition: StateFlow<Float> = combine(
        activeRailSegmentIndex,
        railSegments,
        currentLocatorTotalProgression,
    ) { activeIndex, segments, totalProg ->
        if (totalProg == null || segments.isEmpty()) return@combine 0f
        val totalWeight = segments.fold(0f) { acc, s -> acc + s.weight }
        if (totalWeight == 0f) return@combine 0f
        val i = activeIndex.coerceIn(0, segments.size - 1)
        var weightBefore = 0f
        for (k in 0 until i) weightBefore += segments[k].weight
        val segWeight = (segments.getOrNull(i)?.weight ?: 0f).coerceAtLeast(0f)
        val withinSeg = if (segWeight > 0f) {
            ((totalProg * totalWeight - weightBefore) / segWeight).coerceIn(0f, 1f)
        } else {
            0f
        }
        weightedRailCursorPosition(i, segments, withinSeg)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // ---- Time-remaining estimates -------------------------------------------------------------
    // Reading-speed tracking now lives in [readingSessionCoordinator]; the time-remaining
    // computations below still read [readingSpeedStore] for the persisted speed.

    private data class PositionSnapshot(
        val totalProgression: Float?,
        val chapterProgression: Float?,
        val segments: List<RailSegment>,
        val activeSegmentIndex: Int,
    )

    private val positionSnapshot: Flow<PositionSnapshot> = combine(
        currentLocatorTotalProgression,
        currentLocatorProgression,
        railSegments,
        activeRailSegmentIndex,
    ) { tp, cp, segs, idx -> PositionSnapshot(tp, cp, segs, idx) }

    val chapterTimeRemaining: StateFlow<TimeRemaining?> = combine(
        positionSnapshot,
        playbackState,
        readaloud.readaloudTrackFlow,
        readingSpeedStore.speedSecPerPosition,
    ) { snap, pbState, raTrack, speed ->
        val segments = snap.segments
        val segIdx = snap.activeSegmentIndex

        val totalPositions = segments.fold(0f) { acc, seg -> acc + seg.weight }
        if (totalPositions == 0f) return@combine null

        // If every segment has fallback weight 1f, position data wasn't available — estimates
        // would be meaningless (always ~1 min). Return null until real data is loaded.
        val hasRealPositions = segments.size <= 1 || segments.any { it.weight != 1f }
        if (!hasRealPositions) return@combine null

        if (pbState.connected && raTrack != null) {
            val posGlobal = pbState.positionGlobalSec
            val chapterIdx = pbState.currentChapterIndex
            val chapterEndSec = if (chapterIdx >= 0 && chapterIdx + 1 < raTrack.chapterStartsSec.size) {
                raTrack.chapterStartsSec[chapterIdx + 1]
            } else {
                raTrack.totalDurationSec
            }
            val sec = (chapterEndSec - posGlobal).toLong().coerceAtLeast(0L)
            return@combine TimeRemaining.Exact(sec)
        }

        val chapterWeight = segments.getOrNull(segIdx)?.weight ?: return@combine null
        val totalProg = snap.totalProgression ?: return@combine null
        // Compute where this chapter ends as a fraction of the total book. This works even when a
        // TOC entry spans multiple spine resources (e.g. a "Part I" title page followed by several
        // chapter files) because totalProgression increases monotonically across all resources.
        var weightBefore = 0f
        for (k in 0 until segIdx) weightBefore += segments[k].weight
        val chapterEndFrac = (weightBefore + chapterWeight) / totalPositions
        val remainingFrac = (chapterEndFrac - totalProg).coerceAtLeast(0f)
        val sec = (remainingFrac * totalPositions * speed).toLong().coerceAtLeast(0L)
        TimeRemaining.Estimated(sec)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bookTimeRemaining: StateFlow<TimeRemaining?> = combine(
        positionSnapshot,
        playbackState,
        readaloud.readaloudTrackFlow,
        readingSpeedStore.speedSecPerPosition,
    ) { snap, pbState, raTrack, speed ->
        val segments = snap.segments

        val totalPositions = segments.fold(0f) { acc, seg -> acc + seg.weight }
        if (totalPositions == 0f) return@combine null

        // If every segment has fallback weight 1f, position data wasn't available — estimates
        // would be meaningless (always ~1 min). Return null until real data is loaded.
        val hasRealPositions = segments.size <= 1 || segments.any { it.weight != 1f }
        if (!hasRealPositions) return@combine null

        if (pbState.connected && raTrack != null) {
            val posGlobal = pbState.positionGlobalSec
            val sec = (raTrack.totalDurationSec - posGlobal).toLong().coerceAtLeast(0L)
            return@combine TimeRemaining.Exact(sec)
        }

        val totalProg = snap.totalProgression ?: return@combine null
        val sec = ((1f - totalProg) * totalPositions * speed).toLong().coerceAtLeast(0L)
        TimeRemaining.Estimated(sec)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- SearchController delegations ----------------------------------------------------------

    fun openSearch() = search.openSearch()
    fun closeSearch() = search.closeSearch()
    fun onSearchQueryChanged(query: String) = search.onSearchQueryChanged(query)
    fun nextSearchResult() = search.nextSearchResult()
    fun prevSearchResult() = search.prevSearchResult()

    private val _navigationEvents = Channel<Link>(Channel.CONFLATED)
    val navigationEvents: Flow<Link> = _navigationEvents.receiveAsFlow()

    fun navigateToEntry(entry: TocEntry) {
        val pub = (state.value as? ReaderState.Ready)?.publication ?: return
        val link = pub.tableOfContents.findLinkByHref(entry.href) ?: return
        // A deliberate TOC jump is a "go somewhere new" gesture, not a link tap — drop any pending
        // return card so it can't linger pointing back to a pre-jump position.
        returnNavigator.dismiss()
        _navigationEvents.trySend(link)
        _tocVisible.value = false
    }

    fun navigateToSegment(segment: RailSegment) {
        val pub = (state.value as? ReaderState.Ready)?.publication ?: return
        val link = pub.tableOfContents.findLinkByHref(segment.href) ?: return
        returnNavigator.dismiss()
        _navigationEvents.trySend(link)
    }

    private fun List<Link>.findLinkByHref(href: String): Link? {
        for (link in this) {
            if (link.href.toString() == href) return link
            link.children.findLinkByHref(href)?.let { return it }
        }
        return null
    }

    private suspend fun openPublication(file: File): Publication? {
        val url = AbsoluteUrl("file://${file.absolutePath}") ?: return null
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return null
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> null
        }
    }

    fun updateFormatting(prefs: FormattingPreferences) = formatting.updateFormatting(itemId, prefs)

    fun resetToGlobalDefaults() = formatting.resetToGlobalDefaults(itemId)

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLock.setKeepScreenOn(value) }
    }

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyDispatcher.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyDispatcher.setInvertVolumeKeys(value) }
    }

    // ---- Readaloud playback --------------------------------------------------------------------

    fun openReadaloud() = readaloud.openReadaloud()

    fun closeReadaloud() = readaloud.closeReadaloud()

    private fun persistReadaloudResumePosition(locator: Locator?, fragmentRef: String?) {
        viewModelScope.launch { readaloud.persistReadaloudResumePosition(locator, fragmentRef) }
    }

    fun togglePlayPause() = readaloud.togglePlayPause()

    fun setSpeed(speed: Float) = readaloud.setSpeed(speed)

    /** Persist a debounced-but-not-yet-written speed immediately, so the value a user picks just
     * before dismissing the player isn't lost inside the debounce window. */
    private fun flushPendingSpeed() = readaloud.flushPendingSpeed()

    fun rewind() = readaloud.rewind()

    fun forward() = readaloud.forward()

    /** Delegates to [ReadaloudSession.prepareAudiobookHandoff] (lifted in sub-task 8.4). */
    fun prepareAudiobookHandoff(): Double = readaloud.prepareAudiobookHandoff()

    /** Delegates to [ReadaloudSession.onAudiobookOverlayDismissed] (lifted in sub-task 8.4). */
    fun onAudiobookOverlayDismissed() = readaloud.onAudiobookOverlayDismissed()

    /** Called when the user starts dragging up (before the threshold) — reserved for future pre-warm. */
    fun hintAudiobookHandoff() = readaloud.hintAudiobookHandoff()

    /** Discard any pre-warm state if the drag was abandoned. */
    fun cancelHandoffHint() = readaloud.cancelHandoffHint()

    fun previousChapter() = readaloud.previousChapter()

    fun nextChapter() = readaloud.nextChapter()

    fun onPlayTapped() = readaloud.onPlayTapped()

    fun startReadaloudAtSecond(globalSec: Double) = readaloud.startReadaloudAtSecond(globalSec)

    fun playFromHere(fragmentRef: String) = readaloud.playFromHere(fragmentRef)

    /**
     * Blocks until the sentence-quote map has been built from the SMIL sidecar (or bundle).
     * The "Play from here" selection handler awaits this before resolving the tapped word to a
     * SMIL sentence id — without it, a first-play tap resolves against an empty quote map, falls
     * back to Readium's HTML anchor, and the player restarts the chapter.
     */
    suspend fun ensureSentenceQuotesReady() = readaloud.ensureSentenceQuotesReady()

    fun confirmDownloadAudio(wifiOnly: Boolean) = readaloud.confirmDownloadAudio(wifiOnly)

    fun dismissDownloadPrompt() = readaloud.dismissDownloadPrompt()

    fun onPageTopResolved(href: String, fragmentId: String?) = readaloud.onPageTopResolved(href, fragmentId)
}

/**
 * Decodes the `?source=` nav arg (see MainScreen's EPUB_READER route) into a [ReaderSource],
 * matching case-insensitively against the enum's declared name. [raw] is null for every FullBook
 * open (the arg is omitted entirely) and `"highlights"` for a Highlights-mode open.
 *
 * Deliberately NOT `raw?.replaceFirstChar(Char::uppercase)` + [ReaderSource.valueOf] (the prior
 * implementation): `replaceFirstChar` only touches the FIRST character, so `"fullbook"` normalises
 * to `"Fullbook"` — which does not match the enum entry `FullBook`'s internally-capitalised `B` —
 * while `"FullBook"` only ever matched because no caller passes that literal today. Matching
 * case-insensitively against [ReaderSource.entries] sidesteps the whole class of casing bugs.
 * `internal` so it's unit-testable without constructing [EpubReaderViewModel] (Robolectric-only
 * constraint — see [EpubReaderViewModelHighlightsSourceTest]'s file docstring).
 */
internal fun decodeReaderSource(raw: String?): ReaderSource =
    raw?.let { r -> ReaderSource.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } }
        ?: ReaderSource.FullBook

/**
 * Filters a stream of locator hrefs down to the ones that should trigger a
 * [HighlightsResumeStore] write (Important #3 fix, ADR 0041 Task 10). Two transforms:
 *  - [Flow.distinctUntilChanged] — collapse same-chapter position updates within a chapter to one
 *    write per chapter entered (chapter-level resume precision only, see
 *    [highlightsResumeAnnotationIdForHref]'s docstring).
 *  - [Flow.drop] the first emission — the navigator's own restore of the locator [openBook] just
 *    set as `initialLocator` (read from this exact store moments earlier), not new user progress.
 *    Without this, EVERY Highlights-mode open re-persisted the value it had just read, a wasted
 *    DataStore write on every single open.
 * `internal` (top-level, not inlined into the init block) so this exact transform is
 * unit-testable without constructing [EpubReaderViewModel] (Robolectric-only constraint).
 */
internal fun highlightsResumeHrefUpdates(hrefs: Flow<String>): Flow<String> =
    hrefs.distinctUntilChanged().drop(1)

/**
 * Highlights-mode counterpart to [EpubReaderViewModel.annotationToRender] (Critical #1 fix, ADR
 * 0041). The synthesised Publication has no CFI-addressable ABS EPUB backing it —
 * `lifecycle.publication` and `lifecycle.zip()` are both null in this mode by design (openBook's
 * Highlights branch never calls `lifecycle.open()`) — so the CFI→progression path
 * [EpubReaderViewModel.annotationToRender] uses can never resolve here. Instead this builds the
 * [EpubReaderViewModel.HighlightRender]'s [Locator] directly from [chapters] (the same grouping
 * [HighlightsPublicationFactory] rendered the Publication from): the href is the synthesised
 * chapter's own href, and the locator carries no `locations.progression` at all — Readium's
 * DecorableNavigator falls back to text-based matching against `text.highlight`, which is exactly
 * [a]'s snippet, rendered into that chapter's HTML verbatim (see
 * [HighlightsPublicationFactory.renderChapterHtml] — the `<p class="riffle-hl">` body IS the
 * snippet), so the match is unambiguous. `internal` (top-level, not a VM method) so it's
 * unit-testable without constructing [EpubReaderViewModel].
 *
 * [urlFactory] mirrors [HighlightsPublicationFactory.build]'s same-named parameter: production
 * uses the real `Url(String)` (works fine on-device); JVM tests substitute a factory that builds
 * [Url] instances via `Unsafe.allocateInstance` + `android.net.FakeUri`, because `Url(String)`
 * funnels through `android.net.Uri.parse`, unmocked under this module's stock (non-Robolectric)
 * Android unit-test stub jar (see [HighlightsPublicationFactory]'s class docstring for the same
 * constraint).
 */
internal fun highlightsAnnotationToRender(
    chapters: List<ChapterElision>,
    a: Annotation,
    urlFactory: (String) -> Url? = { Url(it) },
): List<EpubReaderViewModel.HighlightRender> {
    val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }
    val chapterIndex = nonEmptyChapters.indexOfFirst { chapter -> chapter.highlights.any { it.id == a.id } }
    if (chapterIndex < 0) return emptyList()
    val href = "highlights/ch$chapterIndex.xhtml"
    val url = urlFactory(href) ?: return emptyList()
    val locator = Locator(
        href = url,
        mediaType = org.readium.r2.shared.util.mediatype.MediaType.XHTML,
        locations = Locator.Locations(),
        text = Locator.Text(highlight = a.textSnippet),
    )
    // useAccentBarStyle = true routes the highlight through the accent-bar rendering path: no
    // Readium decoration in paginated/vertical, transparent <mark> in continuous, and tap dispatch
    // owned by the injected accent-bar span in the synthesised HTML (see HighlightsPublicationFactory
    // + AnnotationTapUrl). FullBook's annotationToRender leaves this false so tinted highlights and
    // whole-selection tap targets stay unchanged.
    return listOf(EpubReaderViewModel.HighlightRender(a.id, locator, a.color, a.note, useAccentBarStyle = true))
}

/**
 * Fallback chapter title for Highlights mode (ADR 0041) — the href basename with its directory and
 * extension stripped (e.g. "OEBPS/ch03.xhtml" -> "ch03"). [HighlightsPublicationFactory] renders
 * whatever title string it's given verbatim (see [HighlightsPublicationFactoryTest]'s docstring);
 * this is where the actual fallback is computed. `internal` so it's unit-testable from `app:test`.
 */
internal fun deriveChapterTitle(href: String): String {
    val name = href.substringAfterLast('/').substringBeforeLast('.')
    return name.ifBlank { "Chapter" }
}

/**
 * Elided-reader chapter heading, in priority order (ADR 0041 follow-up):
 *  1. Cached TOC entry title (real book chapter name).
 *  2. `deriveChapterTitle(href)` — unless it looks unhelpful (UUID, "unknown", etc.), which
 *     happens for Storyteller-aligned chapters where `chapterHref` is an alignment UUID.
 *  3. `"Chapter N"` fallback using the 1-based position in the elided reading order.
 *
 * `internal` so it's unit-testable from `app:test`.
 */
internal fun elidedChapterTitle(href: String, derived: String, toc: List<TocEntry>, elidedIndex: Int): String {
    resolveChapterTitle(href, toc)?.let { return it }
    if (!looksUnhelpfulTitle(derived)) return derived
    return "Chapter ${elidedIndex + 1}"
}

private val UUID_TITLE_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun looksUnhelpfulTitle(title: String): Boolean =
    title.isBlank() || title.equals("Chapter", ignoreCase = true) || UUID_TITLE_REGEX.matches(title)

/**
 * Resolves a highlight's chapter title from the cached TOC (Fix B, ADR 0041 follow-up) — without
 * this, [deriveChapterTitle]'s href-basename fallback surfaces raw filenames like "part0007" as
 * chapter headings. Matches on the href with any TOC fragment (`#anchor`) stripped, since a
 * highlight's stored `chapterHref` never carries one but a TOC entry pointing at a mid-chapter
 * heading often does. Returns null (not [deriveChapterTitle]'s output) when no TOC entry matches,
 * so the caller can fall back explicitly. `internal` so it's unit-testable from `app:test` without
 * constructing the ViewModel.
 */
internal fun resolveChapterTitle(href: String, toc: List<TocEntry>): String? {
    val normalized = href.substringBefore('#')
    fun search(entries: List<TocEntry>): String? {
        for (entry in entries) {
            val entryHref = entry.href.substringBefore('#')
            if (entryHref == normalized || entryHref.endsWith("/$normalized") || normalized.endsWith("/$entryHref")) {
                return entry.title
            }
            search(entry.children)?.let { return it }
        }
        return null
    }
    return search(toc)
}

/**
 * Filters [rows] down to live highlights, sorts them by reading position (spineIndex, progression,
 * createdAt — see [AnnotationDao.observeAnnotationsByPosition] for the same tie-break order), and
 * groups them into one [ChapterElision] per distinct `chapterHref`, preserving first-encounter
 * order so chapters appear in the synthesised Publication in the order the reader would meet them.
 * `internal` so [EpubReaderViewModel.openBook]'s grouping logic is unit-testable from `app:test`
 * without constructing the (Android-dependency-laden) ViewModel itself.
 */
/**
 * Highlights-mode delete decision (ADR 0041 follow-up): given the DB rows for this book *after*
 * a soft-delete has been applied, returns `true` when the reader should be closed instead of
 * reloaded. Closing is required whenever no live highlights remain — the synthesised Publication
 * would otherwise be built with an empty readingOrder and Readium's navigator crashes on that
 * (reproduced by deleting the last remaining annotation in the annotations reading view).
 *
 * Bookmarks and soft-deleted highlights don't keep the view alive — [buildChapterElisions] already
 * strips both — so a book whose only remaining rows are bookmarks or `deleted=true` highlights is
 * still "empty" from Highlights-mode's perspective.
 *
 * `internal` so this decision is unit-testable from `app:test` without constructing the ViewModel.
 */
internal fun highlightsShouldCloseAfterDelete(rows: List<AnnotationEntity>): Boolean =
    buildChapterElisions(rows).isEmpty()

internal fun buildChapterElisions(rows: List<AnnotationEntity>): List<ChapterElision> {
    val live = rows
        .filter { (it.type == AnnotationEntity.TYPE_HIGHLIGHT || it.type == AnnotationEntity.TYPE_IMAGE) && !it.deleted }
        .sortedWith(compareBy({ it.spineIndex }, { it.progression }, { it.createdAt }))

    val byHref = LinkedHashMap<String, MutableList<AnnotationEntity>>()
    for (row in live) {
        byHref.getOrPut(row.chapterHref) { mutableListOf() }.add(row)
    }

    return byHref.entries.map { (href, highlights) ->
        ChapterElision(
            href = href,
            title = deriveChapterTitle(href),
            highlights = highlights,
        )
    }
}

/**
 * Resumes to chapter-level precision only (Task 10, ADR 0041): the synthesised Publication's
 * [HighlightsPublicationFactory] hrefs — `"highlights/ch$index.xhtml"` — are rebuilt fresh on every
 * open from [chapters]' index order (filtered to non-empty chapters, matching the factory), so
 * there is no stable per-highlight href to persist directly. Instead this re-derives the index of
 * the chapter that contains [lastAnnotationId] and returns that chapter's synthesised href, or null
 * if the highlight no longer exists (deleted since last visit) or [chapters] is empty.
 * [readingOrderSize] guards against an out-of-range index if the two computations ever disagree.
 * `internal` so this chapter-index arithmetic is unit-testable without a real Publication.
 */
internal fun highlightsResumeChapterHref(
    chapters: List<ChapterElision>,
    lastAnnotationId: String,
    readingOrderSize: Int,
): String? {
    val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }
    val index = nonEmptyChapters.indexOfFirst { chapter -> chapter.highlights.any { it.id == lastAnnotationId } }
    if (index < 0 || index >= readingOrderSize) return null
    return "highlights/ch$index.xhtml"
}

/**
 * Inverse of [highlightsResumeChapterHref] (Task 10, ADR 0041): given the synthesised href the
 * navigator just landed on (`"highlights/ch$index.xhtml"`) and the same [chapters] grouping used to
 * build the Publication, returns the id of the first highlight in that chapter — the id persisted
 * as this book's resume position. Returns null for a malformed/out-of-range href (defensive; the
 * navigator should only ever report hrefs the factory itself generated).
 */
internal fun highlightsResumeAnnotationIdForHref(chapters: List<ChapterElision>, href: String): String? {
    val index = Regex("""highlights/ch(\d+)\.xhtml$""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val nonEmptyChapters = chapters.filter { it.highlights.isNotEmpty() }
    return nonEmptyChapters.getOrNull(index)?.highlights?.firstOrNull()?.id
}

/**
 * Cadence's start-position resolver, extracted from [EpubReaderViewModel.onCadencePageTopResolved]
 * for unit-testing without a WebView.
 *
 *  - Happy path: the WebView probe returned `"chapter#cd-N"` (full ref, when the resolver JS
 *    could read the chapter attribute the tokeniser stamped onto <html>) OR a bare `"cd-N"` (old
 *    payload / DOM never tokenised). If it's a full ref we use it directly — bypassing the
 *    Readium-locator-href lag that used to file cd-N under the previous chapter's href.
 *  - Bare id + [href] combined: fall back to the old behaviour, `"$href#$probed"`. Only correct
 *    when Readium's locator href actually matches the currently-rendered DOM, which is not
 *    guaranteed after a paginated chapter turn — but it's the best we can do without the JS
 *    chapter hint.
 *  - Probe failure ([probedFragmentId] null/blank): fall back to the FIRST fragment in
 *    [chapterHrefs] whose value equals [href] — i.e. the first tokenised sentence of the
 *    chapter the user is currently on. The naive alternative — dispatching Start with no
 *    goTo — makes [com.riffle.core.domain.sentence.WpmTicker.play] index into position 0 of
 *    the merged cross-chapter fragment list, which is the first sentence of the FIRST chapter
 *    tokenised this session (often several pages back). That produces the "Cadence starts on a
 *    previous page and Readium scrolls me back" bug.
 *  - Chapter not tokenised yet (rare — `startCadence` gates on a known locator): returns null
 *    so the caller still dispatches Start; the ticker's own default kicks in as a last resort.
 *
 * [knownRefs] is the set of refs the ticker will accept (i.e. `_cadenceQuotes.keys`). When
 * provided, the resolved ref is validated against it — a ref that isn't in the set is rejected
 * (returned as null) rather than passed to a `WpmTicker.goTo` that would silently no-op and
 * cause play() to fall to `orderedFragments[0]`. Old callers that don't know the map can pass
 * `null` to skip the check.
 */
internal fun resolveCadenceStartRef(
    href: String,
    probedFragmentId: String?,
    chapterHrefs: Map<String, String>,
    knownRefs: Set<String>? = null,
): String? {
    val probed = probedFragmentId?.takeIf { it.isNotBlank() }
    val candidate = when {
        probed == null -> chapterHrefs.entries.firstOrNull { it.value == href }?.key
        probed.contains('#') -> probed
        else -> "$href#$probed"
    } ?: return null
    if (knownRefs != null && candidate !in knownRefs) {
        // Reject a stale/mislabeled ref rather than let the ticker fall to position 0.
        // Prefer the CHAPTER carried in [probed] when it's a full ref (JS-provided, DOM-
        // authoritative) over the Kotlin-supplied [href] — the whole reason knownRefs might
        // reject a candidate is that Readium's locator href lagged the tokeniser by one
        // chapter, and re-querying by that stale href would re-introduce the very lag this
        // resolver was written to guard against.
        val fallbackHref = probed?.takeIf { it.contains('#') }?.substringBefore('#') ?: href
        return chapterHrefs.entries.firstOrNull { it.value == fallbackHref }?.key
            ?.takeIf { it in knownRefs }
    }
    return candidate
}

/**
 * Returns true when [newSnippet]/[newAfter] and [existSnippet]/[existAfter] refer to the same
 * region of text — i.e. one highlight should be replaced by the other.
 *
 * Two conditions must BOTH hold:
 *  1. **Text overlap** — one snippet contains the other (substring test, case-insensitive).
 *  2. **Position match** — the "snippet + after-text" context window of each highlight must
 *     contain the other's window. After-text is used (not before-text) so that the check still
 *     passes when the new selection starts earlier than the existing one (a larger selection
 *     covering a smaller word). If [existAfter] is empty (pre-context annotation), position
 *     matching is skipped and text overlap alone is sufficient.
 *
 * The function is `internal` so it can be unit-tested from `app:test`.
 */
internal fun highlightOverlapsAtSamePosition(
    newSnippet: String,
    newAfter: String,
    existSnippet: String,
    existAfter: String,
    contextLen: Int = OVERLAP_CONTEXT_LEN,
): Boolean {
    val newTrimmed = newSnippet.trim().takeIf { it.isNotBlank() } ?: return false
    val existTrimmed = existSnippet.trim().takeIf { it.isNotBlank() } ?: return false
    val textOverlap = newTrimmed.contains(existTrimmed, ignoreCase = true) ||
        existTrimmed.contains(newTrimmed, ignoreCase = true)
    if (!textOverlap) return false
    val existAfterStart = existAfter.take(contextLen)
    if (existAfterStart.isEmpty()) return true
    val newContext = newTrimmed + newAfter.take(contextLen)
    val existContext = existTrimmed + existAfterStart
    return newContext.contains(existContext, ignoreCase = true) ||
        existContext.contains(newContext, ignoreCase = true)
}
