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
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReadingSessionCoordinator
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TimeProvider
import com.riffle.core.domain.TimeRemaining
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.resolveEpubHref
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.app.feature.reader.highlights.ChapterElision
import com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory
import com.riffle.app.feature.reader.highlights.ReaderSource
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

// The audiobook follows the live audio on a tighter cadence than the 30s ebook reconcile, so a
// listen reaches the server within seconds rather than only on the next ebook tick.
// Debounce window for persisting a playback-speed change, so a granular scrub/slide settles to a
// single write rather than one per intermediate 0.05× value.
private const val SPEED_SAVE_DEBOUNCE_MS = 400L
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
    private val serverRepository: ServerRepository,
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
    private val positionOrchestratorFactory: PositionOrchestrator.Factory,
    private val annotationSessionFactory: com.riffle.app.feature.reader.session.AnnotationSession.Factory,
    private val readaloudSessionFactory: com.riffle.app.feature.reader.session.ReadaloudSession.Factory,
    private val readerSessionLifecycleFactory: com.riffle.app.feature.reader.session.ReaderSessionLifecycle.Factory,
    private val logger: Logger,
    private val clock: Clock,
    val dispatchers: DispatcherProvider,
    private val highlightsPublicationFactory: HighlightsPublicationFactory,
    private val annotationDao: AnnotationDao,
) : AndroidViewModel(application) {

    // Formatting/typography/auto-scroll orchestrator — constructed with viewModelScope so
    // teardown is deterministic (the orchestrator's coroutines cancel when the VM is cleared).
    private val formatting: FormattingSession = formattingSessionFactory.create(viewModelScope).also {
        it.setDeviceDensity(application.resources.displayMetrics.density)
    }

    // WakeLock controller — derives keepScreenOn from prefs + autoScroll state.
    private val wakeLock: com.riffle.app.feature.reader.controllers.WakeLockController =
        wakeLockControllerFactory.create(viewModelScope, formatting.autoScrollState)

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
    // (readerSync + audiobookFollow + serverId), and the close-sync guard. The VM keeps the
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
        )

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    // Which content this reader instance displays (ADR 0041). Nav args carry `?source=highlights`
    // (lowercase, see MainScreen's EPUB_READER route); ReaderSource's canonical form is
    // uppercase-first ("Highlights"), so normalise on read. runCatching guards against a garbage
    // query string demoting to FullBook rather than crashing the reader open.
    private val source: ReaderSource =
        savedStateHandle.get<String>("source")
            ?.let { runCatching { ReaderSource.valueOf(it.replaceFirstChar(Char::uppercase)) }.getOrNull() }
            ?: ReaderSource.FullBook

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

    // Nav events the screen can't service itself — e.g. leaving the elided Highlights-mode reader
    // to open the real source book at a tapped highlight's CFI (Task 9, ADR 0041).
    private val _readerNavEvents = Channel<ReaderNavEvent>(Channel.BUFFERED)
    val readerNavEvents: Flow<ReaderNavEvent> = _readerNavEvents.receiveAsFlow()

    // Delegates to the session — the channel lives there now (sub-task 8.3).
    val pageTopProbeRequests: Flow<String> get() = readaloud.pageTopProbeRequests

    private val progressSyncController = ProgressSyncController(
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val readingSessionCoordinator = ReadingSessionCoordinator(
        clock = clock,
        readingSpeedStore = readingSpeedStore,
        scope = viewModelScope,
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
        // Stop Readaloud if it's playing — mutual exclusion (ADR 0037).
        if (playerCoordinator.state.value.connected && playerCoordinator.state.value.isPlaying) {
            playerCoordinator.pause()
        }
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

    // isStorytellerServer and readerServerId now live on [lifecycle] (issue #376). The single
    // remaining read site (annotation binding) captures activeServer + isStorytellerServer from
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

    /** A persisted highlight reconstructed into a renderable Readium locator + colour token + optional note. */
    data class HighlightRender(val id: String, val locator: Locator, val color: String, val note: String?)

    val highlightRenders: StateFlow<List<HighlightRender>> = annotationSession.highlightRenders

    data class HighlightEditTarget(val id: String, val anchorRect: IntRect, val noteOnly: Boolean = false)

    /** Highlight whose actions popup should be open (just-created or tapped), else null. */
    val highlightToEdit: StateFlow<HighlightEditTarget?> = annotationSession.highlightToEdit

    fun openHighlightActions(id: String, anchorRect: IntRect) =
        annotationSession.openHighlightActions(id, anchorRect)

    /** Opens the popup in note-only read mode (no colour pickers, no delete). Used by the margin glyph. */
    fun openNoteReader(id: String, anchorRect: IntRect) =
        annotationSession.openNoteReader(id, anchorRect)

    fun dismissHighlightActions() = annotationSession.dismissHighlightActions()

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
        viewModelScope.launch {
            // Sequential: formatting prefs must be available before openBook() so the
            // navigator never sees the stateIn default on first paint (FormattingSession.bindToBook
            // waits for effectiveFormattingPreferences to reflect the loaded value).
            formatting.bindToBook(itemId)
            openBook()
        }
        // Readaloud start ⇒ stop Auto-Scroll (mutual exclusion, ADR 0037). Stop (not Pause):
        // pausing would leave an invisible Auto-Scroll session waiting to silently resume on
        // Readaloud stop — the surprise the ADR was written to head off.
        viewModelScope.launch {
            playerCoordinator.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing -> formatting.onPlaybackStateChanged(playing) }
        }
        // Panel-open pause/resume is driven from the screen layer (it knows about
        // showFormattingPanel / tocVisible / annotationsPanelVisible / isSearchActive),
        // see [setAutoScrollPaused] below.
        viewModelScope.launch {
            if (!shouldRunReadingSideEffects(source)) return@launch
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                val locator = serverProgressToLocator(serverProgress) ?: return@collect
                // An explicit openAtCfi (annotation tap / search hit) takes precedence over server
                // sync on first open — otherwise ABS's last-read position races in and yanks the
                // reader away from the annotation to wherever the user was reading last.
                // Suppress/emit logic delegated to the orchestrator.
                position.requestServerJumpWithSuppressCheck(locator)
            }
        }
        // NOTE: The sentence-quote build on isPlaying and the audiobook-follow push loop are now
        // owned by ReadaloudSession's own init block (sub-task 8.4). The isPlaying→ReaderStateHolder
        // bridge is wired above via readaloud.onAudioPlayingChanged.
        // NOTE: Server resolution + readaloud.bind() + annotation bind() are all done inside
        // openBook() (Option α), so they run sequentially in a single suspending coroutine.
    }

    private suspend fun openBook() {
        // Highlights mode (ADR 0041): the reader displays a synthesised, elided Publication built
        // from this book's stored highlights rather than the real ABS EPUB container. Diverted
        // before lifecycle.open() so the ABS fetch, matched-sync resolution, readaloud binding, and
        // annotation-sync wiring (all Task 8/9 concerns) never run for this reader instance.
        if (source == ReaderSource.Highlights) {
            val serverId = serverRepository.getActive()?.id
            if (serverId == null) {
                _state.value = ReaderState.Error("No active server")
                return
            }
            val pub = loadHighlightsPublication(serverId, itemId)
            _state.value = ReaderState.Ready(
                publication = pub,
                title = pub.metadata.title ?: "Annotations",
                initialLocator = null,
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

    /**
     * Builds the synthesised, highlights-only Publication for Highlights-mode reading (ADR 0041).
     * Reads this book's live highlights, groups them by chapter (preserving first-encounter order),
     * derives a fallback chapter title from the href basename (Task 6's factory renders whatever
     * title string it's given verbatim — this is where the fallback is actually computed), and hands
     * the result to [HighlightsPublicationFactory].
     */
    private suspend fun loadHighlightsPublication(serverId: String, itemId: String): Publication {
        val rows = annotationDao.getForItem(serverId, itemId)
        return highlightsPublicationFactory.build(
            serverId = serverId,
            itemId = itemId,
            bookTitle = null,
            chapters = buildChapterElisions(rows),
        )
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
            serverId = o.resolvedReaderServerId ?: "",
            itemId = itemId,
            isStorytellerServer = o.isStorytellerServer,
            audioBookId = o.resolvedAudioBookId,
            audioServerId = o.resolvedAudioServerId,
            audioSettingsIdentity = o.resolvedAudioSettingsIdentity,
            audiobookItemId = o.resolvedAudiobookItemId,
            effectiveFormattingPreferencesFlow = formatting.effectiveFormattingPreferences,
            currentLocatorFlow = position.currentLocator,
            readerSyncProvider = { lifecycle.matchedSync.value?.readerSync },
            audiobookFollowProvider = { lifecycle.matchedSync.value?.audiobookFollow },
            readerSyncServerIdProvider = { lifecycle.matchedSync.value?.serverId },
        )

        // Bind the position orchestrator so it can save positions for this book.
        position.bindBook(
            itemId = itemId,
            serverId = o.resolvedReaderServerId ?: "",
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
        if (!o.isStorytellerServer && activeServer != null) {
            annotationServerId = activeServer.id
            // Bind the bookmarks controller so it can observe bookmarks and track the current
            // locator for page-bookmark detection.
            bookmarks.bind(
                serverId = activeServer.id,
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
            // Resolve the ABS-side stable account id (`/api/me` → user.id) as the WebDAV path
            // namespace. A null result means sync is skipped this session; DB stays canonical.
            val namespace = serverRepository.ensureAbsUserId(activeServer.id)
            annotationNamespace = namespace
            annotationSession.bind(
                serverId = activeServer.id,
                namespace = namespace ?: "",
                itemId = itemId,
                highlightRenderResolver = { a -> annotationToRender(a) },
                cfiLocatorResolver = { cfi -> cfiStringToLocator(cfi) },
            )
        }

        // Sync immediately while localUpdatedAt is still the genuine stored value — before the
        // navigator restore emits and would stamp localUpdatedAt = now.
        val syncLocator = o.effectiveInitialLocator
        if (lifecycle.matchedSync.value?.readerSync != null) {
            runReaderSyncCycle(syncLocator)
        } else {
            progressSyncController.sync(itemId, syncLocator?.toPayload() ?: SessionPayload("", 0f))
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
            else progressSyncController.sync(itemId, locator.toPayload())
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
        val serverId = ms.serverId ?: return
        val locJson = (locator ?: position.snapshotLastLocator())?.toJSON()?.toString()
        if (locJson != null) {
            val localUpdatedAt = readingPositionStore.loadLocalUpdatedAt(serverId, itemId)
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
                    readingPositionStore.updateLocalTimestamp(serverId, itemId, result.canonicalLastUpdate)
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
            positionSaveCoordinator.onClose(locator.toJSON().toString(), payload.ebookProgress)
            if (lifecycle.matchedSync.value?.readerSync != null) runReaderSyncCycle(locator)
            else progressSyncController.sync(itemId, payload)
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

    // Create a highlight at the current text selection in the user's last-used colour (see
    // [AnnotationSession.lastUsedHighlightColor]; falls back to yellow first-run). Anchors on a CFI range built from
    // the selection's start progression + selected text (ADR 0024), capturing the snippet + href.
    // Any existing highlights in the same chapter that overlap the new selection are deleted first —
    // a larger selection subsuming a previously highlighted word replaces that highlight.
    fun createHighlight(selectionLocator: Locator, anchorRect: IntRect) {
        if (source == ReaderSource.Highlights) return
        val serverId = annotationServerId ?: return
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
            val progression = selectionLocator.locations.progression ?: 0.0

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

            val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html, progression, snippet)
                ?: return@launch
            val created = annotationStore.createHighlight(
                serverId = serverId,
                itemId = itemId,
                cfi = cfiRange,
                textSnippet = snippet,
                chapterHref = href,
                textBefore = selectionLocator.text.before ?: "",
                textAfter = selectionLocator.text.after ?: "",
                color = annotationSession.lastUsedHighlightColor.value.token,
                spineIndex = spineIndex,
                progression = progression,
            )
            openHighlightActions(created.id, anchorRect)
            scheduleAnnotationSync()
            // observeHighlights re-emits → highlightRenders updates → the screen re-applies decorations.
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
        val serverId = annotationServerId ?: return
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
                annotationStore.createBookmark(
                    serverId = serverId,
                    itemId = itemId,
                    cfi = cfi,
                    textSnippet = snippet,
                    chapterHref = href,
                    spineIndex = spineIdx,
                    progression = prog,
                    bookmarkTitle = title,
                )
                scheduleAnnotationSync()
            }
        }
    }

    /** Recolour an existing highlight; annotationStore re-emits → decoration re-applies. */
    fun recolorHighlight(id: String, color: HighlightColor) {
        viewModelScope.launch { annotationSession.recolorHighlight(id, color) }
    }

    /** Soft-delete a highlight; annotationStore re-emits without it → decoration is removed. */
    fun deleteHighlight(id: String) {
        viewModelScope.launch { annotationSession.deleteHighlight(id) }
    }

    /** Navigate the reader to the annotation with [id], then close the annotations panel. */
    fun navigateToAnnotation(id: String) = annotationSession.navigateToAnnotation(id)

    /** Rename a bookmark; delegates to BookmarksController which calls scheduleAnnotationSync. */
    fun renameBookmark(id: String, title: String) = bookmarks.renameBookmark(id, title)

    /** Soft-delete any annotation (highlight or bookmark); clears highlight-edit state if needed. */
    fun deleteAnnotation(id: String) {
        viewModelScope.launch { annotationSession.deleteAnnotation(id) }
    }

    /** Save (or clear) the note on a highlight; blank text is treated as null (removes the note). */
    fun updateHighlightNote(id: String, note: String?) {
        viewModelScope.launch { annotationSession.updateHighlightNote(id, note) }
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
            val serverId = annotationServerId ?: serverRepository.getActive()?.id ?: return@launch
            _readerNavEvents.send(
                ReaderNavEvent.OpenInSourceBook(
                    serverId = serverId,
                    itemId = row.itemId,
                    cfi = row.cfi,
                ),
            )
        }
    }

    /** Debounced push of the local non-deleted annotations to the WebDAV target (#76). No-op when
     *  sync is not configured or the ABS namespace hasn't been resolved (Storyteller-only, offline
     *  backfill failure). [annotationServerId] and [annotationNamespace] are set together on book
     *  open, so checking the namespace also guarantees the serverId is present.
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
    private suspend fun annotationToRender(a: Annotation): HighlightRender? {
        val pub = lifecycle.publication.value ?: return null
        val spineIndex = epubCfiToSpineIndex(a.cfi) ?: return null
        val link = pub.readingOrder.getOrNull(spineIndex) ?: return null
        val html = readChapterHtml(spineIndex) ?: return null
        val progression = highlightStartProgression(a.cfi, html) ?: return null
        val locator = try {
            Locator.fromJSON(
                JSONObject()
                    .put("href", link.href.toString())
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", progression))
                    .put("text", JSONObject()
                        .put("before", a.textBefore)
                        .put("highlight", a.textSnippet)
                        .put("after", a.textAfter)),
            )
        } catch (_: Exception) {
            null
        } ?: return null
        return HighlightRender(a.id, locator, a.color, a.note)
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
 * Filters [rows] down to live highlights, sorts them by reading position (spineIndex, progression,
 * createdAt — see [AnnotationDao.observeAnnotationsByPosition] for the same tie-break order), and
 * groups them into one [ChapterElision] per distinct `chapterHref`, preserving first-encounter
 * order so chapters appear in the synthesised Publication in the order the reader would meet them.
 * `internal` so [EpubReaderViewModel.loadHighlightsPublication]'s grouping logic is unit-testable
 * from `app:test` without constructing the (Android-dependency-laden) ViewModel itself.
 */
internal fun buildChapterElisions(rows: List<AnnotationEntity>): List<ChapterElision> {
    val live = rows
        .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT && !it.deleted }
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
