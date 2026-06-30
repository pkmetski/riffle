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
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudHighlightColor
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
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import java.util.zip.ZipFile
import javax.inject.Inject

// A reading-position progression change beyond this (or any href change) counts as navigating off the
// page readaloud was parked on; smaller deltas are settle jitter on the same page (ADR 0031).
private const val PARK_PAGE_EPS = 0.001
private const val BOOKMARK_PAGE_EPS = 0.05   // ±5% within-chapter progression window
// The audiobook follows the live audio on a tighter cadence than the 30s ebook reconcile, so a
// listen reaches the server within seconds rather than only on the next ebook tick.
// Debounce window for persisting a playback-speed change, so a granular scrub/slide settles to a
// single write rather than one per intermediate 0.05× value.
private const val SPEED_SAVE_DEBOUNCE_MS = 400L
// Characters of textAfter used to build each highlight's context window for position
// disambiguation in the overlap-detection logic (see createHighlight).
private const val OVERLAP_CONTEXT_LEN = 60

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
    private val logger: Logger,
    private val clock: Clock,
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

    // Readaloud session — owns readaloud state and leaf controls. Full extraction happens across
    // sub-tasks 8.1–8.5; each sub-task lifts more logic here from the VM.
    private val readaloud: com.riffle.app.feature.reader.session.ReadaloudSession =
        readaloudSessionFactory.create(
            scope = viewModelScope,
            snapshotLocator = { position.snapshotLastLocator() },
        ).also { session ->
            // Option-B provider seam (sub-task 8.2): session reads VM's live vars without setters.
            // itemId is wired in init {} because it is declared after readaloud in the class body.
            session.readerSyncProvider = { readerSync }
            session.audiobookFollowProvider = { audiobookFollow }
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

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

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

    private var publication: Publication? = null
    private var epubFile: File? = null
    @Volatile private var epubZip: ZipFile? = null
    private val chapterHtmlCache = mutableMapOf<Int, String>()
    private var closeSyncDone = false
    // Non-null once a matched book's reconciliation prerequisites are cached: the unified cycle then
    // replaces the single-peer ABS/Storyteller paths. [readerSyncServerId] is the active server
    // (the displayed side) that keys the canonical localUpdatedAt.
    private var readerSync: ReaderSyncCoordinator? = null
    // Bundle-SMIL-only audiobook sync used when the full coordinator can't be built (cross-EPUB index
    // not ready). Lets readaloud still sync to the audiobook (ADR 0031). Null when there's no audio
    // target / bundle, or when the full coordinator is present (which supersedes it).
    private var audiobookFollow: AudiobookFollow? = null
    private var readerSyncServerId: String? = null
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

    // True once we know the active server type. Every Storyteller book qualifies (its bundle can
    // be downloaded on demand); ABS books qualify only when a synced bundle is already present.
    private var isStorytellerServer = false

    // The reader's active Server id, resolved inside openBook(). Keys the reconcile-targets cleanup
    // in onCleared(). The session also holds this; kept here so onCleared() doesn't need a session accessor.
    private var readerServerId: String? = null

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

    val readaloudHighlightColor: StateFlow<ReadaloudHighlightColor>
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
        val item = libraryObserver.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> {
                epubFile = result.epubFile
                val pub = openPublication(result.epubFile)
                if (pub == null) {
                    _state.value = ReaderState.Error("Failed to open EPUB")
                    return
                }
                publication = pub
                // Bind the search controller to the new publication so it can execute queries.
                search.bind(pub)

                // ── Option α: resolve active-server + readaloud link sequentially in openBook ──
                // Active-server type decides Readaloud availability: every Storyteller book qualifies,
                // while ABS books qualify only when a synced bundle has already been downloaded.
                val activeServer = serverRepository.getActive()
                isStorytellerServer = activeServer?.serverType == ServerType.STORYTELLER

                // Matched ABS book → key the bundle by the linked Storyteller book id (the bundle
                // is stored under that id, not the ABS item id). Storyteller side keeps itemId.
                val link = if (!isStorytellerServer && activeServer != null) {
                    readaloudLinkRepository.findByAbsItem(activeServer.id, itemId)
                } else {
                    null
                }
                val resolvedAudioBookId = link?.storytellerBookId ?: itemId
                val resolvedAudioServerId = link?.storytellerServerId ?: activeServer?.id ?: ""
                val resolvedReaderServerId = activeServer?.id

                // The audiobook to switch to on swipe-up: among this readaloud's ABS targets, the listenable
                // one (the audiobook in a split library), or this same item if it's a combined ebook+audio.
                val resolvedAudiobookItemId = link?.let { l ->
                    readaloudLinkRepository.findByStorytellerBook(l.storytellerServerId, l.storytellerBookId)
                        .firstOrNull { t ->
                            t.absLibraryItemId != itemId &&
                                libraryObserver.getItem(t.absServerId, t.absLibraryItemId)?.isListenable == true
                        }
                        ?.absLibraryItemId
                }
                logger.d(LogChannel.Handoff) { "RA.audiobookItemId resolved=$resolvedAudiobookItemId (overlay can now mount)" }

                // Resolve the audio-settings key and load the saved speed (ADR 0028). With a link,
                // the resolver prefers the linked audiobook's id; without one, settings key on this ABS item.
                val resolvedAudioSettingsIdentity = if (link != null) {
                    audioIdentityResolver.resolveForStorytellerBook(link.storytellerServerId, link.storytellerBookId)
                } else {
                    AudioIdentity(activeServer?.id ?: "", itemId)
                }
                val resolvedInitialSpeed = audioPlaybackPreferencesStore.load(resolvedAudioSettingsIdentity)
                    ?: listeningPreferencesStore.defaultPlaybackSpeed.first()

                // Mirror the resolved speed into the session.
                readaloud.initialSpeed = resolvedInitialSpeed
                // Claim this book so the durable sweep leaves it to this reader's own cycle (ADR 0030).
                activeServer?.id?.let { openReconcileTargets.markOpen(it, itemId) }

                // ── Bind the readaloud session — must happen before position.bindBook() ────────
                // All per-book readaloud wiring is consolidated here (Option α). After this call,
                // availability flags are set and background work (pre-warm, sidecar, quotes) is launched.
                readaloud.bind(
                    serverId = resolvedReaderServerId ?: "",
                    itemId = itemId,
                    isStorytellerServer = isStorytellerServer,
                    audioBookId = resolvedAudioBookId,
                    audioServerId = resolvedAudioServerId,
                    audioSettingsIdentity = resolvedAudioSettingsIdentity,
                    audiobookItemId = resolvedAudiobookItemId,
                    effectiveFormattingPreferencesFlow = formatting.effectiveFormattingPreferences,
                    currentLocatorFlow = position.currentLocator,
                    readerSyncProvider = { readerSync },
                    audiobookFollowProvider = { audiobookFollow },
                    readerSyncServerIdProvider = { readerSyncServerId },
                )

                // Bind the position orchestrator so it can save positions for this book.
                // readerSyncServerId is now resolved above (Option α); use it directly.
                position.bindBook(
                    itemId = itemId,
                    serverId = resolvedReaderServerId ?: "",
                    positionSaveCoordinator = positionSaveCoordinator,
                    readingPositionStore = readingPositionStore,
                    spinePositionCounts = spinePositionCounts,
                )
                // Stored lastPosition is Readium Locator JSON. Rows written before ADR 0030's
                // translation fix (< 2.6.x) may still hold a raw ABS `epubcfi(...)` — convert
                // those on open so legacy progress isn't lost (one-time healing; new rows are
                // always canonical Locator JSON). A genuinely unusable value falls back to null.
                // A search-result open overrides the saved position; cfiStringToLocator needs `publication`,
                // which is set by this point (same call used just below for legacy CFI healing).
                val openAtLocator = openAtCfi?.takeIf { it.isNotBlank() }?.let { cfiStringToLocator(it) }
                // When openAtCfi resolved, an ABS server-progress event arriving right after open
                // must not stomp the explicit nav intent (annotation tap / search hit). Drop the
                // first server-locator that follows; subsequent syncs (peer / live progress) still
                // apply normally.
                if (openAtLocator != null) position.markSuppressNextServerLocator()
                // Bind openAtCfi to its source annotation (if any) so continuous mode can scroll to
                // the DOM mark for that annotation — a precise post-reflow Y — instead of guessing
                // from char-fraction × measured-WebView-height (which lands short of the highlight
                // when the chapter's text-density differs from char-density).
                val openAtCfiNonBlank = openAtCfi?.takeIf { it.isNotBlank() }
                val initialFocusAnnotationId = if (openAtLocator != null && resolvedReaderServerId != null && openAtCfiNonBlank != null) {
                    runCatching { annotationStore.findByItemAndCfi(resolvedReaderServerId, itemId, openAtCfiNonBlank) }
                        .getOrNull()?.id
                } else null
                val locator = openAtLocator
                    ?: result.lastPosition?.takeIf { it.isNotBlank() }?.let { stored ->
                        runCatching { Locator.fromJSON(JSONObject(stored)) }.getOrNull()
                            ?: cfiStringToLocator(stored)
                    }
                // When a TOC chapter is requested, pass null as initialLocator so Readium's
                // async restore doesn't race with the navigateToEntry nav event. Navigation
                // ownership is handed entirely to navigateToEntry, which waits for
                // ContinuousReaderView.isInitialized before calling navigateTo. The paged
                // navigator similarly receives null and lets fragment.go() handle it.
                _state.value = ReaderState.Ready(
                    publication = pub,
                    title = item.title,
                    initialLocator = if (startTocHref == null) locator else null,
                    initialFocusAnnotationId = if (startTocHref == null) initialFocusAnnotationId else null,
                )
                // Navigate to the requested TOC entry using the same path as an in-reader TOC tap.
                // formattingPrefsProvider in the nav event handler ensures the correct continuous vs
                // paged path is taken even when Compose state hasn't caught up yet.
                startTocHref?.let { navigateToEntry(TocEntry(title = "", href = it)) }
                // Paged-mode only: after initialLocator opens the right chapter, fire the locator
                // through the annotation-nav channel so the post-go() column-snap runs (the
                // architectural invariant: the paged reader never rests off-grid). Without this,
                // Readium's progression-based landing isn't snapped — onPageLoaded deliberately
                // defers snapping to the post-go path. Before suppressNextServerLocator existed,
                // a fast-arriving server-locator event accidentally provided that snap; suppressing
                // it correctly broke the side effect. This restores it explicitly.
                //
                // Continuous mode is INTENTIONALLY excluded: its initialize()/openWindowAt path has
                // its own reflow-tracking re-land logic that lands at the exact target as the
                // chapter measures. A redundant nav event here would race scrollToLoadedChapter
                // (which doesn't reflow-track) against that careful machinery and break the landing.
                // Vertical mode is also excluded — initialLocator already lands correctly without a
                // snap, and a redundant fragment.go() would only add a same-place re-positioning.
                if (openAtLocator != null && formatting.effectiveFormattingPreferences.value.orientation == ReaderOrientation.Horizontal) {
                    annotationSession.emitAnnotationNavigation(openAtLocator)
                }
                // A matched book with cached prerequisites runs the reconciliation cycle instead of
                // the single-peer ABS/Storyteller paths; otherwise this is null and nothing changes.
                readerSync = runCatching { readerSyncFactory.createIfApplicable(itemId) }.getOrNull()
                // readerSyncServerId and readerServerId are the active server resolved earlier via Option α.
                readerSyncServerId = resolvedReaderServerId
                readerServerId = resolvedReaderServerId
                // Update the session's readerSyncServerId now that readerSync is resolved.
                // (bind() set it to resolvedReaderServerId already; this keeps them in sync.)
                readaloud.readerSyncServerId = readerSyncServerId
                // When the full coordinator can't be built (no cross-EPUB index yet), fall back to the
                // bundle-SMIL-only audiobook follow so readaloud still syncs to the audiobook (ADR 0031).
                audiobookFollow = if (readerSync == null) {
                    runCatching { readerSyncFactory.createAudiobookFollowIfApplicable(itemId) }.getOrNull()
                } else null
                // A matched book also drives the audiobook ABS record from this reader, so the sweep
                // must skip that (possibly split-library) item too while the reader is open (ADR 0030).
                readerSyncServerId?.let { sid ->
                    (readerSync?.audioItemId ?: audiobookFollow?.audioItemId)?.let { openReconcileTargets.markOpen(sid, it) }
                }

                // Audiobook→readaloud handoff: opened by swiping the audiobook player down. Auto-start
                // readaloud from the audiobook's position so narration continues where listening stopped.
                // The auto-follow drives the reader page to the narrated sentence once the navigator is up.
                if (startReadaloudAtSec >= 0.0 && readaloud.readaloudAvailable.value) {
                    startReadaloudAtSecond(startReadaloudAtSec)
                }

                // ── Annotation binding — ABS-side only (ADR 0024) ────────────────────────────
                if (!isStorytellerServer && activeServer != null) {
                    annotationServerId = activeServer.id
                    // Bind the bookmarks controller so it can observe bookmarks and track the current
                    // locator for page-bookmark detection.
                    bookmarks.bind(
                        serverId = activeServer.id,
                        itemId = itemId,
                        currentLocator = position.currentLocator,
                    )
                    // Push orientation changes into the controller via a setter (instead of
                    // collecting via combine) so the page-bookmark indicator's match window
                    // re-sizes on a mid-session flip without dragging a third StateFlow into
                    // every locator-update recompute.
                    bookmarks.onOrientationChanged(formatting.formattingPreferences.value.orientation)
                    viewModelScope.launch {
                        formatting.formattingPreferences
                            .map { it.orientation }
                            .distinctUntilChanged()
                            .collect { bookmarks.onOrientationChanged(it) }
                    }
                    // Resolve the ABS-side stable account id (`/api/me` → user.id) as the WebDAV path
                    // namespace. ensureAbsUserId backfills it for legacy server rows. A null result
                    // means we can't sync this session (offline or non-ABS or backfill failed) — the
                    // local DB stays as the source of truth and sync resumes on the next open.
                    val namespace = serverRepository.ensureAbsUserId(activeServer.id)
                    annotationNamespace = namespace
                    // Bind the annotation session: starts observation, syncOnOpen, and live-pull loop.
                    // Observation always starts (shows local highlights). A blank namespace means the
                    // ABS user id wasn't resolved this session — syncOnOpen/scheduleSync/startLiveSync
                    // lambdas delegate to AnnotationSyncController which self-guards via targetProvider.
                    annotationSession.bind(
                        serverId = activeServer.id,
                        namespace = namespace ?: "",
                        itemId = itemId,
                        highlightRenderResolver = { a -> annotationToRender(a) },
                        cfiLocatorResolver = { cfi -> cfiStringToLocator(cfi) },
                    )
                }

                // Sync immediately while localUpdatedAt is still the genuine stored value —
                // before the navigator restore emits and would stamp localUpdatedAt = now.
                if (readerSync != null) {
                    runReaderSyncCycle(locator)
                } else {
                    progressSyncController.sync(itemId, locator?.toPayload() ?: SessionPayload("", 0f))
                }

                // Heartbeat only — no speed-tracker baseline yet (openBook can fire well before the
                // first onReaderResumed; the lifecycle handler below resets the baseline once the
                // reader is actually visible).
                startReadingSession(initialTotalProgression = null)
            }
            is EpubOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
            EpubOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
        }
    }

    private fun startReadingSession(initialTotalProgression: Float?) {
        readingSessionCoordinator.onResumed(
            initialTotalProgression = initialTotalProgression,
            onTick = { syncCurrentPosition() },
        )
    }

    private fun syncCurrentPosition() {
        val locator = position.snapshotLastLocator() ?: return
        viewModelScope.launch {
            if (readerSync != null) runReaderSyncCycle(locator)
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
        val coordinator = readerSync ?: return
        val serverId = readerSyncServerId ?: return
        val locJson = (locator ?: position.snapshotLastLocator())?.toJSON()?.toString()
        if (locJson != null) {
            val localUpdatedAt = readingPositionStore.loadLocalUpdatedAt(serverId, itemId)
            // While parked on the sentence readaloud stopped on, readaloud already wrote the precise
            // audiobook position; reconcile the audiobook inbound-only so this page-derived cycle can't
            // regress it to the page top (ADR 0031). Outbound resumes once the user navigates off the page.
            val pushAudio = readaloud.parkedFragmentRef == null
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
        closeSyncDone = false
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
        readingSessionCoordinator.onClosed(
            currentTotalProgression = currentLocatorTotalProgression.value,
            totalPositions = railSegments.value.fold(0f) { acc, seg -> acc + seg.weight },
        )
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
        if (closeSyncDone) return
        closeSyncDone = true
        // Leaving the book without first pressing X: persist the sentence narrating now so re-entry
        // resumes there. (Pressing X already persisted via closeReadaloud, which closes the player.)
        // Below the closeSyncDone guard so the ON_STOP + onDispose pair doesn't double-write.
        if (readaloud.readaloudOpen.value) {
            persistReadaloudResumePosition(position.snapshotLastLocator(), playerCoordinator.activeFragmentRef.value)
        }
        val locator = position.snapshotLastLocator() ?: return
        // Stays on viewModelScope: runReaderSyncCycle mutates reader state (lastLocator,
        // pendingServerJumpStamp, …) and posts the inbound-jump channel, which must run on the main
        // thread while the screen is alive — it is not safe to relocate to a background flush scope.
        // The durable reading-position write survives a reopen/the offline sweep (ADR 0030).
        viewModelScope.launch {
            val payload = locator.toPayload()
            positionSaveCoordinator.onClose(locator.toJSON().toString(), payload.ebookProgress)
            if (readerSync != null) runReaderSyncCycle(locator)
            else progressSyncController.sync(itemId, payload)
        }
    }

    fun onPanelStateChanged(isOpen: Boolean) {
        readerStateHolder.isPanelOpen = isOpen
    }

    // Translates via character-count CFIs; see EpubCfiTranslator and ADR 0013.
    private suspend fun Locator.toPayload(): SessionPayload = withContext(Dispatchers.Default) {
        val readingOrder = publication?.readingOrder ?: emptyList()
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
        val pub = publication ?: return null
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
        val pub = publication ?: return null
        cfiStringToLocator(serverProgress.ebookLocation)?.let { return it }
        // Fallback: no usable CFI — navigate via book-wide progress float
        val progress = serverProgress.ebookProgress.toDouble().coerceIn(0.0, 1.0)
        return if (progress > 0.0) pub.locateProgression(progress) else null
    }

    // ---- Annotations -------------------------------------------------------------------------

    // Create a yellow highlight at the current text selection. Anchors on a CFI range built from
    // the selection's start progression + selected text (ADR 0024), capturing the snippet + href.
    // Any existing highlights in the same chapter that overlap the new selection are deleted first —
    // a larger selection subsuming a previously highlighted word replaces that highlight.
    fun createHighlight(selectionLocator: Locator, anchorRect: IntRect) {
        val serverId = annotationServerId ?: return
        viewModelScope.launch {
            val pub = publication ?: return@launch
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
     * [BOOKMARK_PAGE_EPS] progression), removes it; otherwise creates a new bookmark anchored to the
     * top-of-viewport CFI with the surrounding text as snippet.
     */
    fun toggleBookmark() {
        val serverId = annotationServerId ?: return
        viewModelScope.launch {
            val locator = position.snapshotLastLocator() ?: return@launch
            val href = locator.href.toString()
            val hrefNorm = normalizeEpubHref(href)
            val prog = locator.locations.progression ?: 0.0
            val existing = bookmarks.bookmarkPositions.value.firstOrNull { bm ->
                bm.chapterHref == hrefNorm && kotlin.math.abs(bm.progression - prog) < BOOKMARK_PAGE_EPS
            }
            if (existing != null) {
                annotationStore.delete(existing.id)
                scheduleAnnotationSync()
            } else {
                val pub = publication ?: return@launch
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
        val pub = publication ?: return null
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
        return withContext(Dispatchers.IO) {
            val file = epubFile ?: return@withContext null
            val pub = publication ?: return@withContext null
            val link = pub.readingOrder.getOrNull(spineIndex) ?: return@withContext null
            val entryPath = normalizeEpubHref(link.href.toString())
            val zip = try {
                epubZip ?: synchronized(this@EpubReaderViewModel) {
                    epubZip ?: ZipFile(file).also { epubZip = it }
                }
            } catch (_: Exception) { return@withContext null }
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
        // Release this book to the durable sweep again (ADR 0030).
        readerServerId?.let { sid ->
            openReconcileTargets.markClosed(sid, itemId)
            (readerSync?.audioItemId ?: audiobookFollow?.audioItemId)?.let { openReconcileTargets.markClosed(sid, it) }
        }
        epubZip?.close()
        epubZip = null
        publication?.close()
        publication = null
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

    fun confirmDownloadAudio(wifiOnly: Boolean) = readaloud.confirmDownloadAudio(wifiOnly)

    fun dismissDownloadPrompt() = readaloud.dismissDownloadPrompt()

    fun onPageTopResolved(href: String, fragmentId: String?) = readaloud.onPageTopResolved(href, fragmentId)
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
