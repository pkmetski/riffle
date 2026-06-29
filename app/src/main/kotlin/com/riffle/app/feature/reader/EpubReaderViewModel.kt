@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

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
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.app.feature.reader.session.AnnotationSession
import com.riffle.app.feature.reader.session.FormattingSession
import com.riffle.app.feature.reader.session.PositionOrchestrator
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudResumePlanner
import com.riffle.core.domain.ReadaloudStartPlan
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TimeRemaining
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.resolveEpubHref
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

private const val SYNC_INTERVAL_MS = 30_000L
// A reading-position progression change beyond this (or any href change) counts as navigating off the
// page readaloud was parked on; smaller deltas are settle jitter on the same page (ADR 0031).
private const val PARK_PAGE_EPS = 0.001
private const val BOOKMARK_PAGE_EPS = 0.05   // ±5% within-chapter progression window
// The audiobook follows the live audio on a tighter cadence than the 30s ebook reconcile, so a
// listen reaches the server within seconds rather than only on the next ebook tick.
private const val AUDIO_PUSH_INTERVAL_MS = 10_000L
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
    private val libraryRepository: LibraryRepository,
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
        )

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

    // Carries the chapter href whose current-page top sentence the screen should resolve against the
    // WebView (only the screen owns it). The screen replies via [onPageTopResolved]. A conflated
    // channel (not a replay-less SharedFlow) so the request survives until the screen's collector
    // receives it — a reopen can emit before the collector re-subscribes after a config change.
    private val _pageTopProbeChannel = Channel<String>(Channel.CONFLATED)
    val pageTopProbeRequests: Flow<String> = _pageTopProbeChannel.receiveAsFlow()

    private val progressSyncController = ProgressSyncController(
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val positionSaveCoordinator = PositionSaveCoordinator<String>(
        savePosition = { cfi ->
            epubRepository.saveReadingPosition(itemId, cfi)
            // Matched book: reading is also listening — persist the translated audiobook position
            // locally so the durable sweep pushes the audio record too, without reopening (ADR 0030).
            mirrorReadingToAudiobook(cfi)
        },
        updateProgress = { progress -> libraryRepository.updateReadingProgress(itemId, progress) },
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
    private var syncJob: Job? = null
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

    // -----------------------------------------------------------------------------------------

    private val _readaloudAvailable = MutableStateFlow(false)
    val readaloudAvailable: StateFlow<Boolean> = _readaloudAvailable

    private val _readaloudVisible = MutableStateFlow(false)
    val readaloudVisible: StateFlow<Boolean> = _readaloudVisible

    // The id under which the synced bundle is stored. For a matched ABS book this is the linked
    // Storyteller book id; for a Storyteller book (or unmatched ABS) it is the opened itemId.
    private var audioBookId: String = itemId
    // The Server the bundle lives on, paired with [audioBookId] to key the file store (ADR 0025).
    // For a matched ABS book this is the linked Storyteller Server; otherwise the active Server.
    private var audioServerId: String = ""

    // The audio-settings key (ADR 0028) — the linked audiobook's id when present, else the Storyteller
    // readaloud id. Distinct from audioBookId/audioServerId, which still locate the readaloud *bundle*.
    private var audioSettingsIdentity: AudioIdentity = AudioIdentity("", itemId)
    // The per-book speed to apply when the player is prepared; the fixed 1x default until loaded.
    private var initialSpeed: Float = AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED

    // The reader's active Server id, resolved once on open. Keys the readaloud resume position (reader
    // space, like the reading position) for both the seed-on-open load and the save-on-close — using
    // one captured id keeps the two symmetric and avoids a per-close getActive() DB read.
    private var readerServerId: String? = null

    // Whether the bottom mini-player / sheet is showing.
    private val _readaloudOpen = MutableStateFlow(false)
    val readaloudOpen: StateFlow<Boolean> = _readaloudOpen

    // Mirrors the controller's playback state for the mini-player controls.
    val playbackState: StateFlow<ReadaloudController.PlaybackState> = playerCoordinator.state

    // The ABS audiobook item to switch to when the mini player is swiped up (the single large player).
    // Resolved in init from this title's readaloud link; null when there's no audiobook to switch to.
    private val _audiobookItemId = MutableStateFlow<String?>(null)
    val audiobookItemId: StateFlow<String?> = _audiobookItemId

    // The text fragment currently narrated — drives the synced highlight. Null clears it.
    val activeFragmentRef: StateFlow<String?> = playerCoordinator.activeFragmentRef

    // How far the live position has advanced through the narrated sentence — drives intra-sentence
    // page turns when a sentence spans more than one paginated column.
    val narrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?> = playerCoordinator.narrationProgress

    // The track is parsed once on first play and reused.
    private var readaloudTrack: com.riffle.core.domain.ReadaloudTrack? = null
    private val _readaloudTrackFlow = MutableStateFlow<com.riffle.core.domain.ReadaloudTrack?>(null)

    // fragmentRef → sentence text quote, so the synced highlight can be anchored by text when
    // Readium has stripped the sentence spans from the rendered (ABS) EPUB. Built once, off the
    // rendered EPUB's own spans, the first time readaloud prepares.
    private val _sentenceQuotes = MutableStateFlow<Map<String, com.riffle.core.domain.SentenceQuote>>(emptyMap())
    val sentenceQuotes: StateFlow<Map<String, com.riffle.core.domain.SentenceQuote>> = _sentenceQuotes

    val readaloudHighlightColor: StateFlow<ReadaloudHighlightColor> =
        readaloudPreferencesStore.preferences
            .map { it.highlightColor }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadaloudHighlightColor.BLUE)

    // span id → bundle chapter href, so "Play from here" can scope the sentence resolver to the chapter
    // being read (a phrase that recurs across chapters otherwise resolves to the wrong chapter's clip).
    private val _sentenceChapters = MutableStateFlow<Map<String, String>>(emptyMap())
    val sentenceChapters: StateFlow<Map<String, String>> = _sentenceChapters

    // Download-prompt state: non-null size means "show the download dialog".
    private val _downloadPromptBytes = MutableStateFlow<Long?>(null)
    val downloadPromptBytes: StateFlow<Long?> = _downloadPromptBytes

    // Non-null when play can't start with the current source — the reason is shown as an in-bar
    // message (offline with no bundle, a refused metered download, or a matched book whose readaloud
    // is neither streamable nor downloaded yet). Null hides the message and shows the controls.
    private val _readaloudBarMessage = MutableStateFlow<String?>(null)
    val readaloudBarMessage: StateFlow<String?> = _readaloudBarMessage

    // True while a download is running, so the bar can show progress text.
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    init {
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
        viewModelScope.launch {
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
            audioBookId = link?.storytellerBookId ?: itemId
            audioServerId = link?.storytellerServerId ?: activeServer?.id ?: ""
            readerServerId = activeServer?.id
            // The audiobook to switch to on swipe-up: among this readaloud's ABS targets, the listenable
            // one (the audiobook in a split library), or this same item if it's a combined ebook+audio.
            _audiobookItemId.value = link?.let { l ->
                readaloudLinkRepository.findByStorytellerBook(l.storytellerServerId, l.storytellerBookId)
                    .firstOrNull { t ->
                        t.absLibraryItemId != itemId &&
                            libraryRepository.getItem(t.absServerId, t.absLibraryItemId)?.isListenable == true
                    }
                    ?.absLibraryItemId
            }
            android.util.Log.d("RIFFLE_HANDOFF", "RA.audiobookItemId resolved=${_audiobookItemId.value} (overlay can now mount)")
            // Pre-warm the SMIL track parse so the first audiobook→readaloud swipe-down skips
            // the ~1.5 s MediaOverlayReader.readTrack() cost (parses every .smil in the bundle).
            // Stored so startReadaloudAtSecond() can join() it instead of double-parsing the zip.
            preWarmTrackJob = viewModelScope.launch {
                readaloudAudioRepository.bundleFile(audioServerId, audioBookId)?.let { bundle ->
                    android.util.Log.d("RIFFLE_HANDOFF", "RA.preWarmTrack start")
                    ensureTrack(bundle)
                    android.util.Log.d("RIFFLE_HANDOFF", "RA.preWarmTrack done (readaloudTrack=${readaloudTrack != null})")
                }
            }
            // Claim this book so the durable sweep leaves it to this reader's own cycle (ADR 0030).
            activeServer?.id?.let { openReconcileTargets.markOpen(it, itemId) }

            // Resolve the audio-settings key and load the saved speed (ADR 0028). With a link, the
            // resolver prefers the linked audiobook's id; without one, settings key on this ABS item.
            audioSettingsIdentity = if (link != null) {
                audioIdentityResolver.resolveForStorytellerBook(link.storytellerServerId, link.storytellerBookId)
            } else {
                AudioIdentity(activeServer?.id ?: "", itemId)
            }
            initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: listeningPreferencesStore.defaultPlaybackSpeed.first()
            // Mirror the resolved speed into the readaloud session so setSpeed delegations (sub-task
            // 8.1) and ensureOpened (via readaloud.initialSpeed) see the same value.
            readaloud.initialSpeed = initialSpeed

            // Restore the readaloud resume position persisted when the book was last left, so the
            // first Play this session continues where narration stopped (same page) or starts at the
            // top of the current page (different page) — the same decision an in-session reopen makes.
            // Keyed by the reader's (serverId, itemId); seeding closeLocator makes the planner treat
            // this as a reopen rather than a first-ever play.
            readerServerId?.let { serverId ->
                readaloudResumeStore.load(serverId, itemId)?.let { saved ->
                    closeLocator = saved.toCloseLocator()
                    resumeFragmentRef = saved.fragmentRef
                }
            }

            val bundlePresent = readaloudAudioRepository.isAudioAvailable(audioServerId, audioBookId)
            val control = readaloudControlState(
                isStoryteller = isStorytellerServer,
                isMatchedAbs = link != null,
                bundlePresent = bundlePresent,
            )
            _readaloudVisible.value = control.visible
            _readaloudAvailable.value = control.enabled

            // Streaming prep (ADR 0028): the moment a matched book is opened, start fetching its sidecar in
            // the background (unless a full bundle is already downloaded — that supersedes streaming). By the
            // time the user taps Play the sidecar is cached, so the streaming session builds without the slow
            // /synced fetch blocking playback. A Play tapped before it's ready arms [autoPlayWhenPrepared].
            if (link != null && !bundlePresent) {
                sidecarStore.prepare(audioServerId, audioBookId)
                viewModelScope.launch {
                    sidecarStore.states.collect { byKey ->
                        when (byKey[sidecarStore.key(audioServerId, audioBookId)]) {
                            ReadaloudSidecarStore.State.Ready -> {
                                        // The sidecar stands in for the bundle for the synced-highlight text quotes
                                // (ADR 0028): build them the moment it's cached, through the SAME
                                // buildSentenceQuotes path the on-disk bundle uses below. Without this the
                                // streaming highlight only ever built on isPlaying, so it never anchored when
                                // playback hadn't started (or stalled) — the quote map stayed empty and
                                // Readium had no text to position the decoration on (spans are stripped).
                                sidecarStore.cachedFile(audioServerId, audioBookId)?.let { sidecar ->
                                    quoteBundle = sidecar
                                    buildSentenceQuotes(sidecar)
                                }
                                if (autoPlayWhenPrepared) {
                                    preparingTimeoutJob?.cancel()
                                    preparingTimeoutJob = null
                                    autoPlayWhenPrepared = false
                                    if (_readaloudBarMessage.value == PREPARING_MESSAGE) _readaloudBarMessage.value = null
                                    onPlayTapped()
                                }
                            }
                            ReadaloudSidecarStore.State.Failed -> {
                                if (autoPlayWhenPrepared) {
                                    preparingTimeoutJob?.cancel()
                                    preparingTimeoutJob = null
                                    autoPlayWhenPrepared = false
                                    _readaloudBarMessage.value =
                                        "Couldn't stream readaloud — download it from the book's details to listen"
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }

            // Build the sentence-quote map eagerly once the readaloud bundle is known to be on disk, so
            // the selection→sentence resolution ("Play from here") and the page-top start probe have it
            // ready before the user can act. It was previously deferred until isPlaying to avoid starving
            // ExoPlayer's audio-start I/O — but at book-open no audio is playing yet, so there's no
            // contention, and a first "Play from here" (before any playback) would otherwise see an empty
            // map and fall back to the chapter start. The isPlaying trigger stays as a backstop for the
            // matched-ABS case where the bundle is downloaded later in the session.
            if (control.enabled) {
                readaloudAudioRepository.bundleFile(audioServerId, audioBookId)?.let { bundle ->
                    quoteBundle = bundle
                    buildSentenceQuotes(bundle)
                }
            }

            // Audiobook→readaloud handoff: opened by swiping the audiobook player down. Auto-start
            // readaloud from the audiobook's position so narration continues where listening stopped.
            // The auto-follow drives the reader page to the narrated sentence once the navigator is up.
            if (startReadaloudAtSec >= 0.0 && control.enabled) {
                startReadaloudAtSecond(startReadaloudAtSec)
            }

            // Annotations are ABS-side only (ADR 0024): available on a non-Storyteller server.
            if (!isStorytellerServer && activeServer != null) {
                annotationServerId = activeServer.id
                // Bind the bookmarks controller so it can observe bookmarks and track the current
                // locator for page-bookmark detection.
                bookmarks.bind(
                    serverId = activeServer.id,
                    itemId = itemId,
                    currentLocator = position.currentLocator,
                )
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
        }
        // Build the sentence-quote map only after audio is actually playing (see ensureTrack): the
        // parse reads the whole bundle, so doing it during audio startup risks starving ExoPlayer on
        // a large book. buildSentenceQuotes is one-shot (quotesBuildStarted), so re-emissions are cheap.
        viewModelScope.launch {
            playbackState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { isPlaying ->
                    // Hand the volume keys to system volume while audio plays; revert on pause/stop.
                    readerStateHolder.isAudioPlaying = isPlaying
                    if (isPlaying) quoteBundle?.let { buildSentenceQuotes(it) }
                }
        }
        // Audiobook-follow: while readaloud narrates a sentence, push the audiobook position derived
        // from the current reading position (which tracks the audio) through the bundle's SMIL, on a
        // tight cadence decoupled from the 30s ebook reconcile so it reaches the server promptly.
        // Writes only the audiobook item, from a page-derived position — never the ebook.
        viewModelScope.launch {
            while (true) {
                delay(AUDIO_PUSH_INTERVAL_MS)
                val playingFragment = playerCoordinator.activeFragmentRef.value
                if (playerCoordinator.state.value.isPlaying && playingFragment != null) {
                    pushAudiobookFromReadingPosition(playingFragment)
                }
            }
        }
    }

    private suspend fun openBook() {
        val item = libraryRepository.getItem(itemId)
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
                // Bind the position orchestrator so it can save positions for this book.
                // Note: readerSyncServerId is resolved in the parallel init coroutine; fall back to
                // a direct server lookup here since openBook runs before that coroutine completes.
                val bindServerId = serverRepository.getActive()?.id ?: ""
                position.bindBook(
                    itemId = itemId,
                    serverId = bindServerId,
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
                val activeServerForAnno = serverRepository.getActive()?.id
                val openAtCfiNonBlank = openAtCfi?.takeIf { it.isNotBlank() }
                val initialFocusAnnotationId = if (openAtLocator != null && activeServerForAnno != null && openAtCfiNonBlank != null) {
                    runCatching { annotationStore.findByItemAndCfi(activeServerForAnno, itemId, openAtCfiNonBlank) }
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
                readerSyncServerId = serverRepository.getActive()?.id
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

                // Sync immediately while localUpdatedAt is still the genuine stored value —
                // before the navigator restore emits and would stamp localUpdatedAt = now.
                if (readerSync != null) {
                    runReaderSyncCycle(locator)
                } else {
                    progressSyncController.sync(itemId, locator?.toPayload() ?: SessionPayload("", 0f))
                }

                startPeriodicSync()
            }
            is EpubOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
            EpubOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                syncCurrentPosition()
            }
        }
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
                        closeLocator = null
                        resumeFragmentRef = null
                        // The jump's own onPositionChanged must keep this adopted server time, not
                        // stamp `now` — else our own sync-move reads back next cycle as a newer local
                        // edit and bounces / pushes the audiobook back. Consumed by that emission.
                        position.setPendingServerJumpStamp(result.canonicalLastUpdate)
                        // The exact narrated sentence at the synced position, so a following "start
                        // readaloud" begins there (not the page top). The column-snap shifts the reader's
                        // progression off the synced point, so the planner's page check can't be relied on
                        // — start from the fragment directly instead. Only when not already narrating.
                        val syncedFragment = coordinator.fragmentForCanonical(json)
                        if (!playerCoordinator.state.value.isPlaying) pendingStartFragmentRef = syncedFragment
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
     * Flush the full readaloud position into the local stores on close/pause (ADR 0031): persist the
     * **sentence-precise** ebook reading position (the narrated sentence's text-anchored locator — the
     * same one used for the highlight, so the ebook reflects exactly where readaloud stopped, not the
     * column), and the local audiobook position (the sentence's audio second via the bundle SMIL),
     * keyed by the audiobook's own ABS item id. The ABS push is done by the caller's
     * [pushAudiobookFromReadingPosition]; this adds the durable local writes so the audiobook and ebook
     * resume there even offline. Matched-only; no-op without a coordinator or a resolvable sentence.
     */
    private suspend fun flushReadaloudPositionToStores(fragmentRef: String?) {
        val serverId = readerSyncServerId ?: return
        if (fragmentRef == null) return
        // Sentence-precise ebook position — independent of the coordinator (just the narrated sentence's
        // text-anchored locator). saveReadingPosition stamps now() and marks the row dirty.
        val sid = fragmentRef.substringAfter('#', "")
        val sentenceJson = readaloudLocatorJson(fragmentRef, _sentenceQuotes.value[sid]).toString()
        epubRepository.saveReadingPosition(itemId, sentenceJson)
        // Local audiobook position at the sentence (SMIL), from the full coordinator or the
        // bundle-SMIL-only follow (ADR 0031), mirroring the just-saved reading row's state.
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        val seconds = readerSync?.audioSecondsForFragment(fragmentRef, position.snapshotLastLocator()?.toJSON()?.toString())
            ?: audiobookFollow?.secondsForFragment(fragmentRef)
            ?: return
        val snap = readingSyncStore.snapshot(serverId, itemId)
        audioSyncStore.mirror(serverId, audioItemId, seconds, snap.localUpdatedAt, snap.lastSyncedAt)
    }

    /**
     * Dual-write the counterpart audiobook position locally (ADR 0030). For a matched book, reading is
     * the same activity as listening, so the just-saved reading position is also persisted into the
     * audiobook store — translated through the bundle's SMIL into the audio second (the exact value the
     * cycle pushes to ABS_AUDIO), keyed by the audiobook's own ABS item id, and stamped with the
     * reading row's current (localUpdatedAt, lastSyncedAt) so both rows carry the same dirty state. The
     * durable sweep then pushes the audiobook record too, without the player ever being opened. Pure
     * additive write to the sibling row — it never touches the reading row. No-op unless matched with an
     * audiobook target and the position is translatable.
     */
    private suspend fun mirrorReadingToAudiobook(canonicalJson: String) {
        val serverId = readerSyncServerId ?: return
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        // ADR 0031: anchor the audiobook on the narrated SENTENCE, never on the page. While a sentence
        // is being spoken, translate THAT fragment to seconds (no page fallback — a page round-trip
        // here is the page-top bug). Only when nothing is narrating (silent reading) deduce the page-top
        // sentence from the page canonical (needs the cross-EPUB index, so null on the bundle-only path).
        // The bundle fragment is the pivot; the page canonical is a fallback only for silent reading,
        // never during an active readaloud (the page-top race — ADR 0031). See [ReadaloudAudioAnchor].
        val seconds = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = playerCoordinator.activeFragmentRef.value,
            readaloudOpen = _readaloudOpen.value,
            parkedFragment = readaloud.parkedFragmentRef,
            fragmentSeconds = { f ->
                readerSync?.audioSecondsForFragment(f, fallbackCanonicalJson = null)
                    ?: audiobookFollow?.secondsForFragment(f)
            },
            pageSeconds = { readerSync?.audioSecondsForCanonical(canonicalJson) },
        ) ?: return
        val snap = readingSyncStore.snapshot(serverId, itemId)
        audioSyncStore.mirror(serverId, audioItemId, seconds, snap.localUpdatedAt, snap.lastSyncedAt)
    }

    /**
     * Responsive audiobook-follow: PATCH only the matched ABS audiobook's currentTime. While a
     * sentence is narrating, it uses the **exact narrated fragment**'s audio time (so the audiobook
     * matches the sentence the user hears, not the top of the page); otherwise it falls back to the
     * reading position through the bundle's SMIL. Never touches the ebook/reading position, so it
     * can't erase or override reading progress. No-op when the book isn't a matched-reconciliation book.
     *
     * Closes the inbound feedback loop: the audiobook is reconciled both ways, so without this our own
     * write would read back next cycle as a "newer remote" and drive the ebook. We record the server
     * timestamp ABS returns as the local timestamp, so the read comes back equal (local wins ties),
     * never newer. All responsive push callers (loop/pause/close) go through here.
     */
    // [fragment] is the narrating sentence to derive the audiobook time from — the exact clip, not the
    // page top. Callers that fire AFTER the player is torn down (close) or paused must pass the value
    // captured BEFORE teardown; passing the now-null live value would silently fall back to the coarse
    // page-based push and send the chapter top to the server.
    private suspend fun pushAudiobookFromReadingPosition(fragment: String?) {
        val serverId = readerSyncServerId ?: return
        val coordinator = readerSync
        val locJson = position.snapshotLastLocator()?.toJSON()?.toString()
        val stamp = runCatching {
            when {
                coordinator != null ->
                    if (fragment != null) coordinator.pushAudiobookForFragment(fragment, locJson)
                    else locJson?.let { coordinator.pushAudiobookProgress(it) }
                // No full coordinator: push the audiobook from the bundle SMIL alone (ADR 0031).
                fragment != null -> audiobookFollow?.pushFragment(fragment)
                else -> null
            }
        }.getOrNull() ?: return
        if (stamp > readingPositionStore.loadLocalUpdatedAt(serverId, itemId)) {
            readingPositionStore.updateLocalTimestamp(serverId, itemId, stamp)
        }
    }

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
        // Readaloud "park" check (Task 8 code — remains in VM until ReadaloudSession is extracted):
        // Leaving the page readaloud stopped on ends the "park": the position is now genuine reading,
        // so reading→audiobook resumes its normal page-top tracking (ADR 0031).
        if (readaloud.parkedFragmentRef != null) {
            val movedOffPage = locator.href.toString() != readaloud.parkedLocatorHref ||
                kotlin.math.abs((locator.locations.progression ?: 0.0) - (readaloud.parkedProgression ?: 0.0)) > PARK_PAGE_EPS
            if (movedOffPage) {
                readaloud.parkedFragmentRef = null
                readaloud.parkedLocatorHref = null
                readaloud.parkedProgression = null
            }
        }
        // All hot-path position state is managed by PositionOrchestrator.
        val (spineHrefs, counts) = spinePositionCounts.value
        position.onPositionChanged(locator, spineHrefs = spineHrefs, spineCounts = counts)
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        position.resetInitialLocatorSeen()
        sessionStartProgression = currentLocatorTotalProgression.value
        sessionStartMs = System.currentTimeMillis()
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            startPeriodicSync()
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
        flushReadingSession()
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        readerStateHolder.isAudioPlaying = false
        syncJob?.cancel()
        storytellerSyncJob?.cancel()
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
        if (_readaloudOpen.value) {
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

    val annotationNavigationEvents: Flow<Locator> = annotationSession.annotationNavigationEvents

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

    // ---- Reading speed tracking & time-remaining estimates -----------------------------------

    private var sessionStartProgression: Float? = null
    private var sessionStartMs: Long = 0L

    private fun flushReadingSession() {
        val startProg = sessionStartProgression ?: return
        sessionStartProgression = null
        val timeDeltaSec = (System.currentTimeMillis() - sessionStartMs) / 1000.0
        val totalProg = currentLocatorTotalProgression.value ?: return
        val progressDelta = totalProg - startProg
        val totalPos = railSegments.value.fold(0f) { acc, seg -> acc + seg.weight }
        if (totalPos == 0f) return
        viewModelScope.launch {
            val prior = readingSpeedStore.speedSecPerPosition.first()
            val updated = ReadingSpeedTracker.recordSession(progressDelta, timeDeltaSec, totalPos, prior)
            if (updated != null) readingSpeedStore.updateSpeed(updated)
        }
    }

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
        _readaloudTrackFlow,
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
        _readaloudTrackFlow,
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

    // Runs the Storyteller single-peer position sync on the same ~30 s cadence as the reading-
    // progress sync, but only while the player is open/playing for a Storyteller book. ABS books
    // keep using the existing ProgressSyncController path untouched.
    private var storytellerSyncJob: Job? = null

    fun openReadaloud() {
        if (!_readaloudAvailable.value) return
        openReadaloudSession()
        // Pressing the reader's readaloud control plays immediately — no separate Play tap.
        // A matched ABS book only enables the control once the bundle is present, so this reaches
        // the bundle-present play path; a Storyteller book matches today's Play-tap behavior.
        onPlayTapped()
    }

    // Opens the readaloud session (shows the player, starts the sync loop) WITHOUT auto-playing.
    // "Play from here" uses this instead of openReadaloud(): it drives its own seek to the selected
    // sentence, and routing through onPlayTapped()'s resume planner would fire a SECOND, competing
    // seek — to the saved resume position or the page-top fallback — that races the selection seek.
    // Whichever landed last won nondeterministically, so "Play from here" sometimes started at the
    // chapter top, sometimes at a stale resume point, sometimes at the selection (the unreliable bug).
    private fun openReadaloudSession() {
        _readaloudOpen.value = true
        startStorytellerSync()
    }

    fun closeReadaloud() {
        _readaloudOpen.value = false
        _downloadPromptBytes.value = null
        _readaloudBarMessage.value = null
        // Persist any speed change still sitting in the debounce window before the session goes away.
        flushPendingSpeed()
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
        // Remember where we stopped before tearing the session down: the sentence narrating now and
        // the reader page it sits on. Reopening uses these to resume in place (same page) or start at
        // the top of the current page (different page) instead of restarting the chapter. Capture
        // before close() — it nulls the active fragment.
        resumeFragmentRef = playerCoordinator.activeFragmentRef.value
        closeLocator = position.snapshotLastLocator()
        // Park on the stopped sentence so the audiobook isn't re-derived from the page top until the
        // user navigates off this page (ADR 0031). Keyed by the reader page we're parked on.
        readaloud.parkedFragmentRef = resumeFragmentRef
        readaloud.parkedLocatorHref = position.snapshotLastLocator()?.href?.toString()
        readaloud.parkedProgression = position.snapshotLastLocator()?.locations?.progression
        // Persist the same stopped position so it survives leaving the book / process death, not just
        // an in-session reopen. Capture before close() nulls the active fragment.
        persistReadaloudResumePosition(closeLocator, resumeFragmentRef)
        // Record where we stopped on the audiobook too, derived from the reading position via the
        // bundle (lastLocator survives close(), so no need to capture the audio clock first).
        val hadFragment = resumeFragmentRef != null
        pendingStartFragmentRef = null
        readaloudPrepared = false
        readaloudStarted = false
        playerCoordinator.close()
        // Use the fragment captured above — close() has nulled the live one. Passing the live value
        // here would push the page top (chapter start) instead of the sentence we stopped on.
        // On the flush scope, not viewModelScope: closing readaloud is routinely followed by leaving
        // the book at once, which cancels viewModelScope and would abort this PATCH mid-write.
        if (hadFragment) progressFlushScope.flush {
            // Durable LOCAL writes FIRST, network PATCH second. Opening the audiobook right after closing
            // readaloud fires its own ABS GET that races this PATCH; if the local audiobook row weren't
            // already written, the entry reconcile would see stale-vs-stale and resume at the old position.
            // Local-first makes the durable row authoritative regardless of the network race or being
            // offline — the ABS push then converges the server (ADR 0030/0031).
            flushReadaloudPositionToStores(resumeFragmentRef)
            pushAudiobookFromReadingPosition(resumeFragmentRef)
        }
    }

    /**
     * Saves where narration stopped for this book, keyed by the reader's (serverId, itemId). Skips
     * when there is no reader page to resume to. Overwrites any previous row so the position persists
     * indefinitely until the next stop — it is not cleared when consumed on resume.
     */
    private fun persistReadaloudResumePosition(locator: Locator?, fragmentRef: String?) {
        val href = locator?.href?.toString() ?: return
        val serverId = readerServerId ?: return
        val progression = locator.locations.progression
        viewModelScope.launch {
            readaloudResumeStore.save(serverId, itemId, ReadaloudResumePosition(href, progression, fragmentRef))
        }
    }

    /** Rebuilds the minimal reader [Locator] the resume planner reads — href + column progression. */
    private fun ReadaloudResumePosition.toCloseLocator(): Locator? = try {
        val locations = JSONObject().also { obj -> progression?.let { obj.put("progression", it) } }
        Locator.fromJSON(
            JSONObject()
                .put("href", href)
                .put("type", "application/xhtml+xml")
                .put("locations", locations)
        )
    } catch (_: Exception) {
        null
    }

    fun togglePlayPause() = readaloud.togglePlayPause()

    fun setSpeed(speed: Float) = readaloud.setSpeed(speed)

    /** Persist a debounced-but-not-yet-written speed immediately, so the value a user picks just
     * before dismissing the player isn't lost inside the debounce window. */
    private fun flushPendingSpeed() = readaloud.flushPendingSpeed()

    fun rewind() = readaloud.rewind()

    fun forward() = readaloud.forward()

    /**
     * Swipe-up handoff to the single large player: capture the current listen second, release the
     * shared player to the audiobook WITHOUT stopping it (so the audiobook keeps playing through the
     * switch), and return the second to hand off. The reader stays in the Compose Navigation back
     * stack (stacking model, not swap), so onCleared is NOT called here — no guard needed.
     */
    fun prepareAudiobookHandoff(): Double {
        val sec = playbackState.value.positionGlobalSec
        val abId = _audiobookItemId.value
        android.util.Log.d("RIFFLE_HANDOFF", "RA.prepareAudiobookHandoff sec=$sec abId=$abId")
        playerCoordinator.releaseForHandoff()
        if (abId != null) audiobookHandoffState.signal(abId, sec)
        return sec
    }

    /**
     * Called when the audiobook overlay is dismissed (back button) without a readaloud handoff.
     * Resets [readaloudPrepared] so the next play re-prepares the media session with the
     * readaloud's audio items (the audiobook replaced them during playback).
     * Also signals the pre-warmed [AudiobookPlayerViewModel] to pause its controller so audiobook
     * audio stops while the reader is visible.
     */
    fun onAudiobookOverlayDismissed() {
        readaloudPrepared = false
        val abId = _audiobookItemId.value
        if (abId != null) audiobookHandoffState.dismiss(abId)
    }

    /** Called when the user starts dragging up (before the threshold) — reserved for future pre-warm. */
    fun hintAudiobookHandoff() = Unit

    /** Discard any pre-warm state if the drag was abandoned. */
    fun cancelHandoffHint() = Unit

    fun previousChapter() = readaloud.previousChapter()

    fun nextChapter() = readaloud.nextChapter()

    /**
     * Play tapped. If a local bundle is present we prepare (if needed) and play. Otherwise: when
     * online, probe the download size and surface the confirm dialog; when offline, surface the
     * "connect to download" message in the bar.
     */
    fun onPlayTapped() {
        _readaloudBarMessage.value = null
        viewModelScope.launch {
            // Bundle precedence (ADR 0028): a downloaded bundle is complete and local — prefer it and skip
            // streaming/prep entirely (the bundle already carries the sidecar content AND the audio).
            val bundle = readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
            if (bundle != null) {
                ensurePreparedAndPlay(bundle)
                return@launch
            }
            // Streaming (ADR 0028): build from the sidecar prepared ahead of time when the book was opened.
            // Instant when the sidecar is already cached; never fetches /synced on this (Play) path.
            if (ensureStreamingSession() != null) {
                ensurePreparedAndPlay(bundle = null)
                return@launch
            }
            // No bundle and no streaming session yet. For a matched book the sidecar may still be preparing
            // — say so and auto-start the moment it's ready, rather than leaving Play a silent no-op. A
            // genuine failure (no audiobook, identity mismatch, dead /synced) points at the bundle download.
            if (audioBookId != itemId) {
                when (sidecarStore.stateOf(audioServerId, audioBookId)) {
                    ReadaloudSidecarStore.State.Preparing -> {
                        _readaloudBarMessage.value = PREPARING_MESSAGE
                        autoPlayWhenPrepared = true
                        preparingTimeoutJob?.cancel()
                        preparingTimeoutJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(PREPARING_SLOW_TIMEOUT_MS)
                            if (autoPlayWhenPrepared) {
                                _readaloudBarMessage.value =
                                    "Taking longer than usual — download it from the book's details to listen offline"
                            }
                        }
                    }
                    else -> {
                        _readaloudBarMessage.value = "Couldn't stream readaloud — download it from the book's details to listen"
                    }
                }
                return@launch
            }
            if (!connectivityObserver.isOnline.value) {
                _readaloudBarMessage.value = "Connect to download readaloud audio"
                return@launch
            }
            // probeSizeBytes may return null (can't probe) — fall back to a zero-sized prompt.
            _downloadPromptBytes.value = readaloudAudioRepository.probeSizeBytes(audioServerId, audioBookId) ?: 0L
        }
    }

    /**
     * Audiobook→readaloud handoff: open readaloud and start narrating from [globalSec] on the readaloud
     * timeline. The audiobook absolute second is used directly — the bundle and ABS audiobook timelines
     * align to ~0s drift (ADR 0031). Mirrors [playFromHere]: opens the session WITHOUT the resume
     * planner so the only seek is this one, and consumes any resume/close position so it can't re-seek
     * away. Falls back to the normal play path (download prompt / offline notice) when no bundle is on
     * disk.
     */
    fun startReadaloudAtSecond(globalSec: Double) {
        if (!_readaloudAvailable.value) return
        val bundle = readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
        if (bundle == null) {
            openReadaloud()
            return
        }
        if (!_readaloudOpen.value) openReadaloudSession()
        readaloudPrepared = false
        viewModelScope.launch {
            preWarmTrackJob?.join()  // wait for background SMIL parse to finish before ensureTrack
            ensureOpened(bundle) ?: return@launch
            readaloudStarted = true
            resumeFragmentRef = null
            closeLocator = null
            playerCoordinator.playFromSecond(globalSec)
        }
    }

    /**
     * Builds the streaming session for this book (cached once built), or null when it isn't streamable
     * (ADR 0028). Only attempts once the sidecar is cached (prepared). A failed attempt is NOT cached:
     * [streamingBuilding] only prevents two concurrent builds (Play + Play-from-here), so a transient
     * identity-check/network blip is retried on the next Play rather than disabling play for the whole
     * session. The retry is cheap — a persisted MISMATCH/NO_AUDIOBOOK short-circuits in the factory
     * without re-fetching, and only a genuinely-recoverable UNKNOWN re-runs the network check.
     */
    private suspend fun ensureStreamingSession(): com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory.Session? {
        streamingSession?.let { return it }
        val cached = sidecarStore.cachedFile(audioServerId, audioBookId)
        if (cached == null) return null
        if (streamingBuilding) return null
        streamingBuilding = true
        try {
            streamingSession = runCatching { streamingSessionFactory.tryBuild(audioServerId, audioBookId) }.getOrNull()
        } finally {
            streamingBuilding = false
        }
        return streamingSession
    }

    /** "Play from here" from the text-selection menu — seek to the clip narrating [fragmentRef]. */
    fun playFromHere(fragmentRef: String) {
        viewModelScope.launch {
            // Streaming (ADR 0028) takes precedence over a local bundle, mirroring onPlayTapped.
            val streaming = ensureStreamingSession() != null
            val bundle = if (streaming) null else readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
            if (!streaming && bundle == null) {
                // No source yet — route through the normal play path (prompt/notify) so the user is
                // told to download first.
                if (!_readaloudOpen.value) openReadaloud() else onPlayTapped()
                return@launch
            }
            // Open the session WITHOUT onPlayTapped()'s resume autoplay: the only seek this session must
            // make is the one below, to the selected sentence. Going through openReadaloud() would race a
            // resume/page-top seek against it (see openReadaloudSession).
            if (!_readaloudOpen.value) openReadaloudSession()
            ensureOpened(bundle) ?: return@launch
            // Starting here counts as the session's first play, so a later pause/resume stays put
            // rather than re-seeking. Consume any pending resume/close position so the resume planner
            // can never re-seek away from the selection.
            readaloudStarted = true
            resumeFragmentRef = null
            closeLocator = null
            // The selection ref is the displayed ABS "href#spanId". On a matched-ABS book the rendered
            // ABS hrefs differ from the Storyteller bundle the clips come from, and span ids recur across
            // chapters — so seeking by the bare id alone lands on the first book-wide occurrence (an
            // earlier chapter), resetting reading progress. Re-key the ref onto the bundle chapter the
            // selection sits in so the player resolves the selected sentence's own clip. Falls back to
            // the original ref when there's no match (unmatched book, or the chapter can't be mapped).
            val seekRef = readerSync?.bundleFragmentRefForSelection(fragmentRef) ?: fragmentRef
            playerCoordinator.playFromHere(seekRef)
        }
    }

    fun confirmDownloadAudio(wifiOnly: Boolean) {
        _downloadPromptBytes.value = null
        // Honour "Wi-Fi only": if the active network is metered, refuse to start the (large) download
        // and surface the same connect-to-download message the offline path uses.
        if (wifiOnly && connectivityObserver.isMetered()) {
            _readaloudBarMessage.value = "Connect to download readaloud audio"
            return
        }
        viewModelScope.launch {
            _downloadProgress.value = 0f
            val result = readaloudAudioRepository.downloadAudio(audioServerId, audioBookId) { downloaded, total ->
                if (total > 0) _downloadProgress.value = downloaded.toFloat() / total.toFloat()
            }
            _downloadProgress.value = null
            when (result) {
                com.riffle.core.domain.AudioDownloadResult.Success -> {
                    // Bundle now present: keep the control visible and enable it.
                    _readaloudVisible.value = true
                    _readaloudAvailable.value = true
                    readaloudAudioRepository.bundleFile(audioServerId, audioBookId)?.let { ensurePreparedAndPlay(it) }
                }
                com.riffle.core.domain.AudioDownloadResult.NoBundle -> Unit
                is com.riffle.core.domain.AudioDownloadResult.NetworkError ->
                    _readaloudBarMessage.value = "Connect to download readaloud audio"
            }
        }
    }

    fun dismissDownloadPrompt() {
        _downloadPromptBytes.value = null
    }

    // Job from the background SMIL pre-warm launched at book-open. startReadaloudAtSecond() joins
    // this before calling ensureTrack so it never double-parses the zip concurrently.
    private var preWarmTrackJob: Job? = null

    // True once the controller has been pointed at the bundle this session, so resuming after a
    // pause doesn't re-prepare (which would reset playback to the start).
    private var readaloudPrepared = false

    // Streaming session for this book (ADR 0028), built once. Non-null → audio streams from ABS and
    // the sidecar stands in for the bundle (track + highlight quotes). Null after an attempt → bundle.
    private var streamingSession: com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory.Session? = null
    // Guards against two concurrent build attempts (Play + Play-from-here). Unlike a permanent "attempted"
    // flag it does NOT cache a failure, so a transient identity-check blip is retried on the next Play.
    private var streamingBuilding = false

    // Set when the user taps Play while the sidecar is still preparing; the prepare-state observer starts
    // playback once it flips to Ready (ADR 0028).
    private var autoPlayWhenPrepared = false
    private var preparingTimeoutJob: kotlinx.coroutines.Job? = null

    // True once playback has been started this session, so the first play seeks to the reader's
    // position while a later resume-after-pause stays where it was.
    private var readaloudStarted = false

    // The reader position when the player was last closed, and the sentence narrating at that moment.
    // Non-null only after a close (not a pause): they drive the resume-vs-page-top decision on reopen.
    private var closeLocator: Locator? = null
    private var resumeFragmentRef: String? = null

    // The exact narrated sentence a server sync placed the reader at (set in runReaderSyncCycle on an
    // inbound jump). The next "start readaloud" begins here so it matches the synced audiobook
    // position precisely, instead of the page top. Ignored once the reader leaves that chapter.
    // NOTE: accessed via readaloud.pendingStartFragmentRef after sub-task 8.1.
    private var pendingStartFragmentRef: String? = null

    private suspend fun ensurePreparedAndPlay(bundle: File?) {
        ensureOpened(bundle) ?: return
        // Record the active readaloud so a media-notification tap reopens this book's reader.
        nowPlayingStore.set(com.riffle.app.playback.NowPlaying.Readaloud(itemId))
        if (readaloudStarted) {
            // Resume after a pause: rewind by the configured amount then play.
            val rewindSec = rewindOnResumeSec.value
            if (rewindSec > 0) playerCoordinator.skipBy(-rewindSec)
            playerCoordinator.play()
            return
        }
        readaloudStarted = true

        // Reconcile the readaloud start against the LOCAL audiobook position (ADR 0031): if the
        // audiobook was advanced more recently than the reading position (e.g. an offline listen, or a
        // listen whose ABS push hasn't landed), start readaloud at that listen position's sentence —
        // derived bundle-SMIL-only (audio seconds → fragment), so it works even without the cross-EPUB
        // index or an ABS round-trip. Last-update-wins across the two local stores.
        val localAudioStartFragment: String? = run {
            val sid = readerSyncServerId ?: return@run null
            val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return@run null
            val audioSnap = audioSyncStore.snapshot(sid, audioItemId)
            ReadaloudStartAnchor.fromLocalAudio(
                audioSeconds = audioSnap.position,
                audioUpdatedAt = audioSnap.localUpdatedAt,
                readingUpdatedAt = readingSyncStore.snapshot(sid, itemId).localUpdatedAt,
                fragmentForAudioSeconds = { s -> readerSync?.fragmentForAudioSeconds(s) ?: audiobookFollow?.fragmentForAudioSeconds(s) },
            )
        }

        // Matched book: readaloud starts at the reconciled reading position. There is no
        // separate "first sentence of the page" concept — Play resumes where listening/reading last
        // was; a specific sentence is reached via Play-from-here. (A matched audiobook is optional —
        // when present it's the source of #1 below; without one the reconcile is just ebook↔Storyteller
        // and Play falls through to the local readaloud position.) The start resolves, in order:
        //   1. the exact remote sentence a server sync just placed the reader at (pendingStartFragmentRef,
        //      set in runReaderSyncCycle when a remote peer — typically the audiobook — won the reconcile)
        //      — only while still in that chapter, else the reader has moved on and it's stale;
        //   2. the local last-played sentence saved on close (resumeFragmentRef);
        //   3. the sentence the current reading position falls in (fragmentAt via the bundle).
        // Falls back to the reading position's chapter only when nothing narrated anchors it (e.g. front
        // matter); resolveStartClip declines rather than restarting the book.
        readerSync?.let { coordinator ->
            val lastLoc = position.snapshotLastLocator()
            val pending = pendingStartFragmentRef?.takeIf { p ->
                lastLoc?.href?.let { resolveEpubHref(it.toString()) } == resolveEpubHref(p.substringBefore('#'))
            }
            val startFragment = localAudioStartFragment
                ?: pending
                ?: resumeFragmentRef
                ?: lastLoc?.toJSON()?.toString()?.let { coordinator.fragmentForCanonical(it) }
            pendingStartFragmentRef = null
            closeLocator = null
            resumeFragmentRef = null
            if (startFragment != null) {
                playerCoordinator.playFromHere(startFragment)
            } else {
                lastLoc?.href?.let { playerCoordinator.playFromReaderPosition(it.toString(), null) }
            }
            return
        }

        // Matched book without the full coordinator (no cross-EPUB index): a newer local listen still
        // seeds the readaloud start from the bundle SMIL (ADR 0031) — closes the audiobook→readaloud
        // asymmetry index-free.
        if (localAudioStartFragment != null) {
            pendingStartFragmentRef = null
            closeLocator = null
            resumeFragmentRef = null
            playerCoordinator.playFromHere(localAudioStartFragment)
            return
        }

        // Storyteller-only readaloud (no canonical reconciliation): there is no reconciled position to
        // anchor to, so the page-top probe remains the way to find the page's first sentence on a first
        // play / reopen-elsewhere, with resumeFragmentRef for an in-place reopen.
        val closed = closeLocator
        val resume = resumeFragmentRef
        closeLocator = null
        resumeFragmentRef = null
        val loc = position.snapshotLastLocator()
        val plan = ReadaloudResumePlanner.plan(
            isScroll = effectiveFormattingPreferences.value.orientation != ReaderOrientation.Horizontal,
            closeHref = closed?.href?.toString(),
            closeProgression = closed?.locations?.progression,
            resumeFragmentRef = resume,
            currentHref = loc?.href?.toString(),
            currentProgression = loc?.locations?.progression,
        )
        when (plan) {
            ReadaloudStartPlan.FromReaderPosition ->
                // First play of this session: start at the first FULL sentence visible on the reader's
                // current page — not the chapter top, and not the book start. The reader locator carries
                // no sentence fragment, so we route through the same page-top probe the reopen path uses:
                // it asks the WebView for the first narrated sentence whose start is on the page and
                // replies via onPageTopResolved(), which starts playback there. Falls back to plain play
                // only when there is no reader page at all.
                //
                // For the streaming path (sidecar just became ready), the sentence-quote map may still
                // be building when the probe fires, so the WebView returns null → onPageTopResolved with
                // null fragmentId → resolveStartClip with href only. Skip the probe in that case and
                // call playFromReaderPosition directly: it uses the sameChapter() tolerance to find the
                // chapter's first clip even when ABS and Storyteller hrefs differ by an OEBPS prefix,
                // giving a chapter-top start rather than leaving the player paused.
                if (loc != null) {
                    if (_sentenceQuotes.value.isNotEmpty()) {
                        _pageTopProbeChannel.trySend(loc.href.toString())
                    } else {
                        playerCoordinator.playFromReaderPosition(loc.href.toString(), null)
                    }
                } else {
                    playerCoordinator.play()
                }
            is ReadaloudStartPlan.Resume -> playerCoordinator.playFromHere(plan.fragmentRef)
            // Reopened on a different page: the screen resolves the page's first sentence against the
            // WebView and replies via onPageTopResolved(); play starts there.
            is ReadaloudStartPlan.PageTop -> _pageTopProbeChannel.trySend(plan.href)
        }
    }

    /**
     * The screen resolved the first sentence visible on [href]'s current page ([fragmentId]), or null
     * when none could be located. Starts narration there — chapter top when the id is null. Ignored if
     * the player was closed during the (async) probe round-trip, so a late reply can't revive it.
     */
    fun onPageTopResolved(href: String, fragmentId: String?) {
        if (!_readaloudOpen.value) return
        playerCoordinator.playFromReaderPosition(href, fragmentId)
    }

    private suspend fun ensureOpened(bundle: File?): com.riffle.core.domain.ReadaloudTrack? {
        val track = ensureTrack(bundle) ?: return null
        if (!readaloudPrepared) {
            val session = streamingSession
            if (session != null) {
                // Audio caches lazily as it plays ("when needed") — the cache persists on disk, so a
                // normal listen gradually makes the book offline. Eager full-fetch is the explicit
                // "Download readaloud" action only (ADR 0028), not automatic here.
                playerCoordinator.openStreaming(session.streaming, track)
            } else {
                playerCoordinator.open(audioBookId, bundle!!, track)
            }
            // Apply the persisted per-book speed to the freshly-prepared session. Use the coordinator
            // directly (not the VM's setSpeed) so restoring the saved value doesn't re-save it.
            playerCoordinator.setSpeed(readaloud.initialSpeed)
            readaloudPrepared = true
        }
        return track
    }

    private suspend fun ensureTrack(bundle: File?): com.riffle.core.domain.ReadaloudTrack? {
        readaloudTrack?.let { return it }
        // Streaming (ADR 0028): track comes from the sidecar (already parsed); the sidecar also feeds
        // the highlight quotes, standing in for the bundle. Otherwise read the track from the bundle.
        val session = streamingSession
        val track: com.riffle.core.domain.ReadaloudTrack
        if (session != null) {
            track = session.track
            quoteBundle = session.sidecarFile
        } else {
            val b = bundle ?: return null
            track = readaloudAudioRepository.readTrack(audioServerId, audioBookId) ?: return null
            // Defer the (heavy, whole-bundle) sentence-quote parse until audio is actually playing, so it
            // never competes with ExoPlayer's audio-start I/O on the same multi-hundred-MB zip.
            quoteBundle = b
        }
        readaloudTrack = track
        return track
    }

    // Stashed so the deferred quote parse (kicked once playback first reaches "playing") knows which
    // bundle to read. See the playbackState observer in init.
    @Volatile private var quoteBundle: File? = null

    // Extracts per-sentence text quotes from the *Storyteller bundle* — the EPUB that actually carries
    // the sentence spans (the ABS ebook may be the plain publisher EPUB without them). Keyed by span
    // id, these feed the text-anchored highlight locator; Readium then finds the sentence by text in
    // the rendered ABS page. Off the main thread; a corrupt/empty bundle just leaves the map empty.
    // Set synchronously the first time the parse is kicked, so a rapid pause→resume (two isPlaying
    // transitions before the IO completes) or a bundle that yields no quotes can't re-launch the
    // heavy whole-bundle parse. Guarding on _sentenceQuotes (written only at the end of the IO) would
    // not cover either case.
    @Volatile private var quotesBuildStarted = false

    private fun buildSentenceQuotes(bundle: File) {
        if (quotesBuildStarted) return
        quotesBuildStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chapters = com.riffle.core.domain.EpubContentExtractor.extract(bundle)?.chapters ?: return@launch
                _sentenceQuotes.value = com.riffle.core.domain.ReadaloudTextQuotes.build(chapters)
                _sentenceChapters.value = com.riffle.core.domain.ReadaloudTextQuotes.sentenceChapterHrefs(chapters)
            } catch (e: Throwable) {
                // Never let the highlight-quote parse crash playback; the highlight just stays absent.
                android.util.Log.e("RIFFLE_RA", "buildSentenceQuotes failed", e)
            }
        }
    }

    private fun startStorytellerSync() {
        if (!isStorytellerServer) return
        // A matched book runs the canonical reconciliation cycle; don't also run the standalone Storyteller loop.
        if (readerSync != null) return
        if (storytellerSyncJob?.isActive == true) return
        storytellerSyncJob = viewModelScope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                val locator = position.snapshotLastLocator() ?: continue
                when (val outcome = storytellerSyncController.runCycle(itemId, locator.toJSON().toString())) {
                    is StorytellerSyncOutcome.PulledRemote -> {
                        // The stored canonical position is the Readium locator JSON — deserialize and
                        // jump the navigator there (reusing the server-locator channel the screen
                        // already wires to fragment.go()).
                        try {
                            val pulled = Locator.fromJSON(JSONObject(outcome.locatorJson))
                            if (pulled != null) position.requestServerJump(pulled)
                        } catch (_: Exception) { /* malformed remote locator — ignore */ }
                    }
                    StorytellerSyncOutcome.PushedLocal,
                    StorytellerSyncOutcome.InSync,
                    StorytellerSyncOutcome.Offline -> Unit
                }
            }
        }
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
