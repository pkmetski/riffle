@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudController
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerSyncOutcome
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReaderTheme
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
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TimeRemaining
import com.riffle.core.domain.resolveEpubHref
import com.riffle.core.domain.withResolvedTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.publication.services.search.SearchService
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
// The audiobook follows the live audio on a tighter cadence than the 30s ebook reconcile, so a
// listen reaches the server within seconds rather than only on the next ebook tick.
private const val AUDIO_PUSH_INTERVAL_MS = 10_000L
// Debounce window for persisting a playback-speed change, so a granular scrub/slide settles to a
// single write rather than one per intermediate 0.05× value.
private const val SPEED_SAVE_DEBOUNCE_MS = 400L

sealed class ReaderState {
    data object Loading : ReaderState()
    data class Ready(
        val publication: Publication,
        val title: String,
        val initialLocator: Locator?,
    ) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val epubRepository: EpubRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val readingSessionRepository: ReadingSessionRepository,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,
    private val volumeNavigationController: VolumeNavigationController,
    private val timeProvider: TimeProvider,
    private val readerStateHolder: ReaderStateHolder,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
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
    private val nowPlayingStore: com.riffle.app.playback.NowPlayingStore,
    private val progressFlushScope: ProgressFlushScope,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val readingSpeedStore: ReadingSpeedStore,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    // Audiobook→readaloud handoff: when opened by swiping the audiobook player down, this carries the
    // audiobook's current position (seconds on the concatenated timeline; -1 = not a handoff). On open
    // we auto-start readaloud playing from this second so narration continues where listening left off.
    // Also observed as a flow so a back-stack return (reader was alive behind the audiobook player) can
    // receive an updated value set by the audiobook player when it pops back.
    private val startReadaloudAtSec: Double =
        (savedStateHandle.get<Float>("startReadaloudAtSec") ?: -1f).toDouble()

    // Cached WebView host view and Readium fragment — kept alive across composable removal so the
    // Readium WebView does not reload when the reader re-surfaces from the back stack after the
    // audiobook player was on top. Cleared in onCleared to prevent leaks on permanent navigation
    // away. The Activity context embedded in the view is validated in EpubNavigatorView before
    // reuse so rotation clears and recreates the cache safely (see EpubReaderScreen).
    //
    // Storing a View in a ViewModel is non-standard; it is justified here because the WebView
    // reload (1–2 s) was the dominant latency in the audiobook↔readaloud switch, and all
    // alternative approaches (overlay composables, Fragment-retain tricks) required far larger
    // architectural changes. The cache is guarded against rotation via a Context identity check.
    internal var savedNavigatorContainer: Any? = null   // ScrollBoundaryNavigationContainer
    internal var savedNavigatorFragment: EpubNavigatorFragment? = null
    internal var savedNavigatorFragmentIsDoublePage: Boolean? = null

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

    private val _serverLocatorChannel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = _serverLocatorChannel.receiveAsFlow()

    private var lastLocator: Locator? = null
    val latestLocator: Locator? get() = lastLocator
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
    private var initialLocatorSeen = false
    // Set to the winning server timestamp when the cycle drives a remote-win jump; consumed by the
    // jump's resulting onPositionChanged. That emission persists the CFI (so a reopen lands on the
    // synced page) but keeps this server timestamp instead of stamping `now` — the jump is not a
    // genuine local edit, and stamping `now` would make it read back next cycle as a newer LOCAL
    // change, bouncing the reader and pushing the audiobook back over a newer server position.
    private var pendingServerJumpStamp: Long? = null

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys: StateFlow<Boolean> = volumeKeyPreferencesStore.invertVolumeKeys
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

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    // Per-field book overrides. null on a field means "follow global", so changing global later
    // propagates to the book for fields the user hasn't touched in the panel.
    private val _bookOverrides = MutableStateFlow(BookFormattingOverrides())

    // Effective prefs = global ⊕ overrides. Updated optimistically on user change so the
    // navigator sees the new value without waiting for the Room write to complete.
    private val _formattingPreferences = MutableStateFlow(FormattingPreferences())
    val formattingPreferences: StateFlow<FormattingPreferences> = _formattingPreferences

    // Ticks emitted at each ThemeSchedule boundary so the resolved theme can flip live
    // during a reading session. Re-armed whenever the schedule changes.
    private val scheduleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1).apply {
        tryEmit(Unit) // prime the combine() below so it emits immediately on collection
    }

    // The user's pick with `theme` replaced by the resolver's concrete value when the
    // pick is Auto. Every downstream consumer (Readium navigator submitPreferences,
    // chapter rail backdrop, palette) reads this — they stay ignorant of Auto.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        scheduleTicks,
    ) { prefs, _ -> prefs.withResolvedTheme(timeProvider.nowLocalTime()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())

    private val _hasBookOverrides = MutableStateFlow(false)
    val hasBookOverrides: StateFlow<Boolean> = _hasBookOverrides

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<Locator>>(emptyList())
    val searchResults: StateFlow<List<Locator>> = _searchResults

    private val _currentSearchIndex = MutableStateFlow(-1)
    val currentSearchIndex: StateFlow<Int> = _currentSearchIndex

    private val _searchNavigationChannel = Channel<Locator>(Channel.CONFLATED)
    val searchNavigationEvents: Flow<Locator> = _searchNavigationChannel.receiveAsFlow()

    private var searchJob: Job? = null

    // ---- Readaloud (ADR 0023) ----------------------------------------------------------------

    // True once we know the active server type. Every Storyteller book qualifies (its bundle can
    // be downloaded on demand); ABS books qualify only when a synced bundle is already present.
    private var isStorytellerServer = false

    // ---- Annotations (ADR 0024 / 0025) -------------------------------------------------------

    // The ABS server hosting this item; annotations key on it together with itemId. Resolved once
    // the active server is known. Null on the Storyteller-only / Readaloud side, where annotations
    // are absent.
    private var annotationServerId: String? = null

    // Highlights exist only while reading the ABS side. False on a Storyteller-only book — the
    // "Highlight" affordance must not appear there (ADR 0024).
    private val _annotationsAvailable = MutableStateFlow(false)
    val annotationsAvailable: StateFlow<Boolean> = _annotationsAvailable

    /** A persisted highlight reconstructed into a renderable Readium locator + colour token. */
    data class HighlightRender(val id: String, val locator: Locator, val color: String)

    private val _highlightRenders = MutableStateFlow<List<HighlightRender>>(emptyList())
    val highlightRenders: StateFlow<List<HighlightRender>> = _highlightRenders

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

    // Set when play is tapped offline with no local bundle — shown as an in-bar message.
    private val _readaloudOfflineMessage = MutableStateFlow(false)
    val readaloudOfflineMessage: StateFlow<Boolean> = _readaloudOfflineMessage

    // True while a download is running, so the bar can show progress text.
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    init {
        viewModelScope.launch {
            // Sequential: prefs must be available before openBook() so initialPreferences is set correctly.
            loadFormattingPreferences()
            openBook()
        }
        // Keep effective prefs in sync with both global changes and override updates. This is
        // why per-book settings are sparse: a global change to a field the user hasn't touched
        // in this book flows through immediately.
        viewModelScope.launch {
            combine(
                formattingPreferencesStore.preferences,
                _bookOverrides,
            ) { global, overrides -> overrides.applyTo(global) to !overrides.isEmpty }
                .collect { (effective, hasOverrides) ->
                    _formattingPreferences.value = effective
                    _hasBookOverrides.value = hasOverrides
                }
        }
        viewModelScope.launch {
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                serverProgressToLocator(serverProgress)?.let { _serverLocatorChannel.trySend(it) }
            }
        }
        viewModelScope.launch {
            _formattingPreferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    // Degenerate schedule (equal day/night times) collapses to always-day —
                    // no boundary will ever arrive. Park until cancelled (the schedule
                    // changes) instead of spinning at 1 Hz against the 1_000L floor.
                    if (schedule.dayStart == schedule.nightStart) {
                        awaitCancellation()
                    }
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val nowSec = now.toSecondOfDay().toLong()
                        val nextSec = next.toSecondOfDay().toLong()
                        val delayMs = (((nextSec - nowSec + 24 * 3600) % (24 * 3600)) * 1000L)
                            .coerceAtLeast(1_000L)
                        delay(delayMs)
                        scheduleTicks.tryEmit(Unit)
                    }
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

            val control = readaloudControlState(
                isStoryteller = isStorytellerServer,
                isMatchedAbs = link != null,
                bundlePresent = readaloudAudioRepository.isAudioAvailable(audioServerId, audioBookId),
            )
            _readaloudVisible.value = control.visible
            _readaloudAvailable.value = control.enabled

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
                _annotationsAvailable.value = true
                observeHighlights(activeServer.id)
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
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .collect { query ->
                    searchJob?.cancel()
                    if (query.length < 2) {
                        _searchResults.value = emptyList()
                        _currentSearchIndex.value = -1
                        return@collect
                    }
                    searchJob = launch { performSearch(query) }
                }
        }
        // Back-stack return: the audiobook player was popped while the reader was alive behind it.
        // The audiobook sets startReadaloudAtSec in our savedStateHandle before calling popBackStack(),
        // so we observe subsequent emissions (drop(1) skips the initial value handled in the
        // control.enabled block above) and auto-start readaloud at the new position.
        viewModelScope.launch {
            savedStateHandle.getStateFlow("startReadaloudAtSec", -1f)
                .drop(1)
                .filter { it >= 0f }
                .collect { sec -> startReadaloudAtSecond(sec.toDouble()) }
        }
    }

    private suspend fun loadFormattingPreferences() {
        val overrides = bookFormattingPreferencesStore.load(itemId)
        val global = formattingPreferencesStore.preferences.first()
        _bookOverrides.value = overrides
        _formattingPreferences.value = overrides.applyTo(global)
        _hasBookOverrides.value = !overrides.isEmpty
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
                // Stored lastPosition is Readium Locator JSON. Rows written before ADR 0030's
                // translation fix (< 2.6.x) may still hold a raw ABS `epubcfi(...)` — convert
                // those on open so legacy progress isn't lost (one-time healing; new rows are
                // always canonical Locator JSON). A genuinely unusable value falls back to null.
                val locator = result.lastPosition?.takeIf { it.isNotBlank() }?.let { stored ->
                    runCatching { Locator.fromJSON(JSONObject(stored)) }.getOrNull()
                        ?: cfiStringToLocator(stored)
                }
                _state.value = ReaderState.Ready(
                    publication = pub,
                    title = item.title,
                    initialLocator = locator,
                )
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
        val locator = lastLocator ?: return
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
        val locJson = (locator ?: lastLocator)?.toJSON()?.toString()
        if (locJson != null) {
            val localUpdatedAt = readingPositionStore.loadLocalUpdatedAt(serverId, itemId)
            // While parked on the sentence readaloud stopped on, readaloud already wrote the precise
            // audiobook position; reconcile the audiobook inbound-only so this page-derived cycle can't
            // regress it to the page top (ADR 0031). Outbound resumes once the user navigates off the page.
            val pushAudio = parkedFragmentRef == null
            val result = runCatching { coordinator.runCycle(locJson, localUpdatedAt, pushAudio) }.getOrNull()
            if (result != null) {
                result.jumpLocatorJson?.let { json ->
                    runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()?.let { loc ->
                        _serverLocatorChannel.trySend(loc)
                        // A server sync (e.g. a newer audiobook listen) moved the reader. Make the synced
                        // position the reader's position for EVERY readaloud-start input, not just the
                        // persisted resume row: the tracked locator, and the in-memory close/resume state
                        // the planner reads (both seeded at book-open, before this sync). Without this, a
                        // later "start readaloud" resumes the STALE pre-sync sentence and jumps the reader
                        // (and the synced ebook+audiobook) back to the old position — the erase.
                        lastLocator = loc
                        closeLocator = null
                        resumeFragmentRef = null
                        // The jump's own onPositionChanged must keep this adopted server time, not
                        // stamp `now` — else our own sync-move reads back next cycle as a newer local
                        // edit and bounces / pushes the audiobook back. Consumed by that emission.
                        pendingServerJumpStamp = result.canonicalLastUpdate
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
        val seconds = readerSync?.audioSecondsForFragment(fragmentRef, lastLocator?.toJSON()?.toString())
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
            parkedFragment = parkedFragmentRef,
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
        val locJson = lastLocator?.toJSON()?.toString()
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
        _footnotePopup.value = FootnotePopupState(content)
    }

    fun dismissFootnotePopup() {
        _footnotePopup.value = null
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
        lastLocator = locator
        _currentLocatorHref.value = locator.href.toString()
        _currentLocatorProgression.value = locator.locations.progression?.toFloat()
        locator.locations.totalProgression?.toFloat()?.let { _currentLocatorTotalProgression.value = it }
        // Leaving the page readaloud stopped on ends the "park": the position is now genuine reading,
        // so reading→audiobook resumes its normal page-top tracking (ADR 0031).
        if (parkedFragmentRef != null) {
            val movedOffPage = locator.href.toString() != parkedLocatorHref ||
                kotlin.math.abs((locator.locations.progression ?: 0.0) - (parkedProgression ?: 0.0)) > PARK_PAGE_EPS
            if (movedOffPage) {
                parkedFragmentRef = null
                parkedLocatorHref = null
                parkedProgression = null
            }
        }
        if (!initialLocatorSeen) {
            initialLocatorSeen = true
            return
        }
        // If this emission is the reader settling onto a position the cycle jumped it to (a remote
        // win), persist the CFI but restore the server timestamp the cycle adopted — see
        // pendingServerJumpStamp. A genuine user navigation leaves the flag null and stamps `now`.
        val serverJumpStamp = pendingServerJumpStamp
        pendingServerJumpStamp = null
        viewModelScope.launch {
            positionSaveCoordinator.onChanged(locator.toJSON().toString())
            if (serverJumpStamp != null) {
                readerSyncServerId?.let { readingPositionStore.updateLocalTimestamp(it, itemId, serverJumpStamp) }
            }
        }
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        initialLocatorSeen = false
        sessionStartProgression = currentLocatorTotalProgression.value
        sessionStartMs = System.currentTimeMillis()
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            startPeriodicSync()
        }
    }

    fun onReaderClosed() {
        flushReadingSession()
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        readerStateHolder.isAudioPlaying = false
        syncJob?.cancel()
        storytellerSyncJob?.cancel()
        if (closeSyncDone) return
        closeSyncDone = true
        // Leaving the book without first pressing X: persist the sentence narrating now so re-entry
        // resumes there. (Pressing X already persisted via closeReadaloud, which closes the player.)
        // Below the closeSyncDone guard so the ON_STOP + onDispose pair doesn't double-write.
        if (_readaloudOpen.value) {
            persistReadaloudResumePosition(lastLocator, playerCoordinator.activeFragmentRef.value)
        }
        val locator = lastLocator ?: return
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
        val docPath = extractCfiDocPath(cfi)
        val spineIndex = epubCfiToSpineIndex(cfi)
        val link = spineIndex?.let { pub.readingOrder.getOrNull(it) }
        val html = spineIndex?.let { readChapterHtml(it) }
        val chapterProgression = if (docPath != null && html != null) cfiDocPathToProgression(docPath, html) else null
        if (link == null || chapterProgression == null) return null
        return try {
            Locator.fromJSON(
                JSONObject()
                    .put("href", link.href.toString())
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", chapterProgression))
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

    // Keeps highlightRenders in sync with the store. Re-runs when the book finishes opening
    // (state → Ready) so persisted highlights re-render on reopen, and whenever the set changes.
    private fun observeHighlights(serverId: String) {
        viewModelScope.launch {
            combine(
                annotationStore.observeHighlights(serverId, itemId),
                state,
            ) { annotations, st -> annotations to (st is ReaderState.Ready) }
                .collect { (annotations, ready) ->
                    _highlightRenders.value =
                        if (!ready) emptyList() else annotations.mapNotNull { annotationToRender(it) }
                }
        }
    }

    // Create a yellow highlight at the current text selection. Anchors on a CFI range built from
    // the selection's start progression + selected text (ADR 0024), capturing the snippet + href.
    fun createHighlight(selectionLocator: Locator) {
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
            val cfiRange = buildHighlightCfiRangeForSelection(spineStep, html, progression, snippet)
                ?: return@launch
            annotationStore.createHighlight(
                serverId = serverId,
                itemId = itemId,
                cfi = cfiRange,
                textSnippet = snippet,
                chapterHref = href,
            )
            // observeHighlights re-emits → highlightRenders updates → the screen re-applies decorations.
        }
    }

    // Reconstruct a persisted highlight into a renderable Readium locator. The CFI start re-anchors
    // the within-chapter position; the text snippet lets Readium's decorator locate the range.
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
                    .put("text", JSONObject().put("highlight", a.textSnippet)),
            )
        } catch (_: Exception) {
            null
        } ?: return null
        return HighlightRender(a.id, locator, a.color)
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
        // Release this book to the durable sweep again (ADR 0030).
        readerServerId?.let { sid ->
            openReconcileTargets.markClosed(sid, itemId)
            (readerSync?.audioItemId ?: audiobookFollow?.audioItemId)?.let { openReconcileTargets.markClosed(sid, it) }
        }
        epubZip?.close()
        epubZip = null
        // Drop the cached WebView container so it can be garbage-collected. In the stacking
        // navigation model the reader's onCleared fires only when the user permanently leaves
        // the reader (back from reader, navigate to home, etc.) — at that point the audiobook
        // has already been popped and stopped, so there is no live session to protect.
        savedNavigatorContainer = null
        savedNavigatorFragment = null
        savedNavigatorFragmentIsDoublePage = null
        // Tear down the audio session so it doesn't outlive the reader (clears the highlight too).
        playerCoordinator.close()
        // Readaloud can't outlive the reader, so this session is no longer playing.
        nowPlayingStore.clearIf { it is com.riffle.app.playback.NowPlaying.Readaloud && it.itemId == itemId }
        // Cancel the coordinator's state-collection scope so it isn't leaked past this ViewModel.
        playerCoordinator.dispose()
    }

    private val _currentLocatorHref = MutableStateFlow<String?>(null)
    val currentLocatorHref: StateFlow<String?> = _currentLocatorHref

    private val _currentLocatorProgression = MutableStateFlow<Float?>(null)
    val currentLocatorProgression: StateFlow<Float?> = _currentLocatorProgression

    // Whole-book progress (0..1) for the reading "% read" label — the same coordinate persisted as
    // ebookProgress and shown in book details. Distinct from railCursorPosition, which is a
    // chapter-weighted fraction over TOC segments only and so diverges from the stored progress.
    // Updated only when the navigator emits a non-null totalProgression: a null (positions not yet
    // computed) holds the last real value rather than falling back to the within-chapter progression.
    // Null before the first Readium locator arrives so callers can distinguish "unknown" from 0%.
    private val _currentLocatorTotalProgression = MutableStateFlow<Float?>(null)
    val currentLocatorTotalProgression: StateFlow<Float?> = _currentLocatorTotalProgression

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible

    fun openToc() { _tocVisible.value = true }
    fun closeToc() { _tocVisible.value = false }

    val tocEntries: StateFlow<List<TocEntry>> = state
        .map { (it as? ReaderState.Ready)?.publication?.tableOfContents?.toTocEntries() ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val railSegments: StateFlow<List<RailSegment>> = state
        .map { s ->
            val ready = s as? ReaderState.Ready ?: return@map emptyList()
            val base = buildRailSegments(ready.publication.tableOfContents.toTocEntries(), ready.title)
            val pub = ready.publication
            val positions: List<List<Locator>> = try {
                pub.positionsByReadingOrder()
            } catch (_: Throwable) {
                emptyList()
            }
            val spineHrefs = pub.readingOrder.map { it.url().toString() }
            val positionCounts = positions.map { it.size }
            val weighted = weightSegmentsByChapterLength(base, spineHrefs, positionCounts)
            weighted
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

    fun openSearch() {
        _isSearchActive.value = true
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        searchJob?.cancel()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun nextSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val next = (_currentSearchIndex.value + 1).coerceAtMost(results.size - 1)
        _currentSearchIndex.value = next
        _searchNavigationChannel.trySend(results[next])
    }

    fun prevSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prev = (_currentSearchIndex.value - 1).coerceAtLeast(0)
        _currentSearchIndex.value = prev
        _searchNavigationChannel.trySend(results[prev])
    }

    private suspend fun performSearch(query: String) {
        val pub = publication ?: return
        val service = pub.findService(SearchService::class)
        if (service == null) {
            _searchResults.value = emptyList()
            _currentSearchIndex.value = -1
            return
        }
        val results = try {
            withContext(Dispatchers.IO) {
                chapterHtmlCache.clear()
                val iterator = service.search(query)
                val acc = mutableListOf<Locator>()
                try {
                    while (true) {
                        val pageResult = iterator.next()
                        // isFailure = chapter unreadable (e.g. OOM wrapped by Readium as
                        // SearchError.Reading); skip this chapter and continue to the next.
                        // getOrNull() == null = end of book; stop.
                        if (pageResult.isFailure) continue
                        val page = pageResult.getOrNull() ?: break
                        acc.addAll(page.locators)
                    }
                } finally {
                    iterator.close()
                }
                acc
            }
        } catch (_: OutOfMemoryError) {
            emptyList()
        }
        _searchResults.value = results
        if (results.isEmpty()) {
            _currentSearchIndex.value = -1
        } else {
            _currentSearchIndex.value = 0
            _searchNavigationChannel.trySend(results[0])
        }
    }

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

    fun updateFormatting(prefs: FormattingPreferences) {
        val previousEffective = _formattingPreferences.value
        val updated = _bookOverrides.value.withChanges(previousEffective, prefs)
        _bookOverrides.value = updated
        _formattingPreferences.value = prefs
        _hasBookOverrides.value = !updated.isEmpty
        viewModelScope.launch { bookFormattingPreferencesStore.save(itemId, updated) }
    }

    fun resetToGlobalDefaults() {
        viewModelScope.launch {
            bookFormattingPreferencesStore.clear(itemId)
            _bookOverrides.value = BookFormattingOverrides()
            _formattingPreferences.value = formattingPreferencesStore.preferences.first()
            _hasBookOverrides.value = false
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
    }

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setInvertVolumeKeys(value) }
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
        _readaloudOfflineMessage.value = false
        // Persist any speed change still sitting in the debounce window before the session goes away.
        flushPendingSpeed()
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
        // Remember where we stopped before tearing the session down: the sentence narrating now and
        // the reader page it sits on. Reopening uses these to resume in place (same page) or start at
        // the top of the current page (different page) instead of restarting the chapter. Capture
        // before close() — it nulls the active fragment.
        resumeFragmentRef = playerCoordinator.activeFragmentRef.value
        closeLocator = lastLocator
        // Park on the stopped sentence so the audiobook isn't re-derived from the page top until the
        // user navigates off this page (ADR 0031). Keyed by the reader page we're parked on.
        parkedFragmentRef = resumeFragmentRef
        parkedLocatorHref = lastLocator?.href?.toString()
        parkedProgression = lastLocator?.locations?.progression
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

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            // Record the audiobook position on pause too (the follow loop is gated on isPlaying, which
            // is about to go false), derived from the reading position via the bundle.
            val pausedFragment = playerCoordinator.activeFragmentRef.value
            playerCoordinator.pause()
            // Park on the paused sentence too (same reasoning as closeReadaloud): otherwise the 30s
            // reconcile cycle, gated on parkedFragmentRef, would push the page-top audio position and
            // regress the audiobook below where playback paused. Cleared on the first navigation off
            // this page (ADR 0031).
            if (pausedFragment != null) {
                parkedFragmentRef = pausedFragment
                parkedLocatorHref = lastLocator?.href?.toString()
                parkedProgression = lastLocator?.locations?.progression
            }
            // Flush scope (see closeReadaloud): a pause is often immediately followed by leaving the
            // book, which would cancel a viewModelScope-launched PATCH before it reaches ABS.
            if (pausedFragment != null) progressFlushScope.flush {
                // Local-first, then PATCH — same race fix as closeReadaloud (ADR 0031).
                flushReadaloudPositionToStores(pausedFragment)
                pushAudiobookFromReadingPosition(pausedFragment)
            }
        } else {
            onPlayTapped()
        }
    }

    private var speedSaveJob: Job? = null
    // The speed whose persistence is still pending behind the debounce, so close can flush it
    // (null once written).
    private var pendingSpeed: Float? = null

    fun setSpeed(speed: Float) {
        // Apply to the live player immediately; persist debounced so a granular scrub/slide (which
        // fires many intermediate values) only writes the settled speed, not every 0.05 step.
        playerCoordinator.setSpeed(speed)
        // Keep the value ensureOpened() reapplies on reopen in sync with the live speed. closeReadaloud
        // resets readaloudPrepared, so an in-session reopen re-runs ensureOpened and would otherwise
        // restore the stale book-open speed — losing the change the user just made.
        initialSpeed = speed
        pendingSpeed = speed
        speedSaveJob?.cancel()
        speedSaveJob = viewModelScope.launch {
            delay(SPEED_SAVE_DEBOUNCE_MS)
            if (audioSettingsIdentity.serverId.isEmpty()) return@launch
            audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed)
            pendingSpeed = null
        }
    }

    /** Persist a debounced-but-not-yet-written speed immediately, so the value a user picks just
     * before dismissing the player isn't lost inside the debounce window. */
    private fun flushPendingSpeed() {
        val speed = pendingSpeed ?: return
        speedSaveJob?.cancel()
        pendingSpeed = null
        if (audioSettingsIdentity.serverId.isEmpty()) return
        progressFlushScope.flush { audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed) }
    }

    fun rewind() = playerCoordinator.skipBy(-rewindIntervalSec.value)

    fun forward() = playerCoordinator.skipBy(skipIntervalSec.value)

    /**
     * Swipe-up handoff to the single large player: capture the current listen second, release the
     * shared player to the audiobook WITHOUT stopping it (so the audiobook keeps playing through the
     * switch), and return the second to hand off. The reader stays in the Compose Navigation back
     * stack (stacking model, not swap), so onCleared is NOT called here — no guard needed.
     */
    fun prepareAudiobookHandoff(): Double {
        val sec = playbackState.value.positionGlobalSec
        playerCoordinator.releaseForHandoff()
        return sec
    }

    /** Called when the user starts dragging up (before the threshold) — reserved for future pre-warm. */
    fun hintAudiobookHandoff() = Unit

    /** Discard any pre-warm state if the drag was abandoned. */
    fun cancelHandoffHint() = Unit

    fun previousChapter() = playerCoordinator.previousChapter()

    fun nextChapter() = playerCoordinator.nextChapter()

    /**
     * Play tapped. If a local bundle is present we prepare (if needed) and play. Otherwise: when
     * online, probe the download size and surface the confirm dialog; when offline, surface the
     * "connect to download" message in the bar.
     */
    fun onPlayTapped() {
        _readaloudOfflineMessage.value = false
        val bundle = readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
        if (bundle != null) {
            viewModelScope.launch { ensurePreparedAndPlay(bundle) }
            return
        }
        // A matched ABS book's bundle is provisioned from the book's detail screen against the
        // linked Storyteller server (the active server here is ABS and can't serve it). The reader
        // control is disabled until the bundle exists; this also guards the "Play from here"
        // selection path, which can reach onPlayTapped() directly. So: no local bundle + matched
        // ABS book → do nothing (no wrong-server probe/download).
        if (audioBookId != itemId) return
        if (!connectivityObserver.isOnline.value) {
            _readaloudOfflineMessage.value = true
            return
        }
        viewModelScope.launch {
            // NOTE: probe/download here use the ACTIVE server. The matched-ABS case (audioBookId !=
            // itemId) is handled by the guard above, so only Storyteller books (active server ==
            // Storyteller) reach this path, and audioBookId resolves correctly.
            // probeSizeBytes may return null (can't probe) — fall back to a zero-sized prompt so
            // the user can still choose to download.
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
        viewModelScope.launch {
            ensureOpened(bundle) ?: return@launch
            readaloudStarted = true
            resumeFragmentRef = null
            closeLocator = null
            playerCoordinator.playFromSecond(globalSec)
        }
    }

    /** "Play from here" from the text-selection menu — seek to the clip narrating [fragmentRef]. */
    fun playFromHere(fragmentRef: String) {
        val bundle = readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
        if (bundle == null) {
            // No bundle yet — route through the normal play path (prompt/notify) so the user is
            // told to download first.
            if (!_readaloudOpen.value) openReadaloud() else onPlayTapped()
            return
        }
        // Open the session WITHOUT onPlayTapped()'s resume autoplay: the only seek this session must
        // make is the one below, to the selected sentence. Going through openReadaloud() would race a
        // resume/page-top seek against it (see openReadaloudSession).
        if (!_readaloudOpen.value) openReadaloudSession()
        viewModelScope.launch {
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
            _readaloudOfflineMessage.value = true
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
                    _readaloudOfflineMessage.value = true
            }
        }
    }

    fun dismissDownloadPrompt() {
        _downloadPromptBytes.value = null
    }

    // True once the controller has been pointed at the bundle this session, so resuming after a
    // pause doesn't re-prepare (which would reset playback to the start).
    private var readaloudPrepared = false

    // True once playback has been started this session, so the first play seeks to the reader's
    // position while a later resume-after-pause stays where it was.
    private var readaloudStarted = false

    // The reader position when the player was last closed, and the sentence narrating at that moment.
    // Non-null only after a close (not a pause): they drive the resume-vs-page-top decision on reopen.
    private var closeLocator: Locator? = null
    private var resumeFragmentRef: String? = null

    // "Parked" on the sentence readaloud last stopped on (set on close, while the reader stays on that
    // page). While parked, reading→audiobook uses THIS sentence — not the page-top — so a debounced
    // reading-save or a reconcile cycle firing after close can't regress the audiobook below where
    // readaloud stopped (ADR 0031). Cleared on the first genuine navigation off the parked page.
    private var parkedFragmentRef: String? = null
    private var parkedLocatorHref: String? = null
    private var parkedProgression: Double? = null

    // The exact narrated sentence a server sync placed the reader at (set in runReaderSyncCycle on an
    // inbound jump). The next "start readaloud" begins here so it matches the synced audiobook
    // position precisely, instead of the page top. Ignored once the reader leaves that chapter.
    private var pendingStartFragmentRef: String? = null

    private suspend fun ensurePreparedAndPlay(bundle: File) {
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
            val pending = pendingStartFragmentRef?.takeIf { p ->
                lastLocator?.href?.let { resolveEpubHref(it.toString()) } == resolveEpubHref(p.substringBefore('#'))
            }
            val startFragment = localAudioStartFragment
                ?: pending
                ?: resumeFragmentRef
                ?: lastLocator?.toJSON()?.toString()?.let { coordinator.fragmentForCanonical(it) }
            pendingStartFragmentRef = null
            closeLocator = null
            resumeFragmentRef = null
            if (startFragment != null) {
                playerCoordinator.playFromHere(startFragment)
            } else {
                lastLocator?.href?.let { playerCoordinator.playFromReaderPosition(it.toString(), null) }
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
        val loc = lastLocator
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
                if (loc != null) {
                    _pageTopProbeChannel.trySend(loc.href.toString())
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

    private suspend fun ensureOpened(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        val track = ensureTrack(bundle) ?: return null
        if (!readaloudPrepared) {
            playerCoordinator.open(audioBookId, bundle, track)
            // Apply the persisted per-book speed to the freshly-prepared session. Use the coordinator
            // directly (not the VM's setSpeed) so restoring the saved value doesn't re-save it.
            playerCoordinator.setSpeed(initialSpeed)
            readaloudPrepared = true
        }
        return track
    }

    private suspend fun ensureTrack(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        readaloudTrack?.let { return it }
        val track = readaloudAudioRepository.readTrack(audioServerId, audioBookId) ?: return null
        readaloudTrack = track
        _readaloudTrackFlow.value = track
        // Defer the (heavy, whole-bundle) sentence-quote parse until audio is actually playing, so it
        // never competes with ExoPlayer's audio-start I/O on the same multi-hundred-MB zip. The
        // highlight is only meaningful once playback is running anyway, so a beat's delay is invisible.
        quoteBundle = bundle
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
                val locator = lastLocator ?: continue
                when (val outcome = storytellerSyncController.runCycle(itemId, locator.toJSON().toString())) {
                    is StorytellerSyncOutcome.PulledRemote -> {
                        // The stored canonical position is the Readium locator JSON — deserialize and
                        // jump the navigator there (reusing the server-locator channel the screen
                        // already wires to fragment.go()).
                        try {
                            val pulled = Locator.fromJSON(JSONObject(outcome.locatorJson))
                            if (pulled != null) _serverLocatorChannel.trySend(pulled)
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
