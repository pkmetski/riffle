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
import com.riffle.core.domain.BookFormattingOverrides
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
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SessionPayload
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
import org.json.JSONObject
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
// The audiobook follows the live audio on a tighter cadence than the 30s ebook reconcile, so a
// listen reaches the server within seconds rather than only on the next ebook tick.
private const val AUDIO_PUSH_INTERVAL_MS = 10_000L

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
    private val connectivityObserver: ConnectivityObserver,
    private val threePeerSyncFactory: ThreePeerReaderSyncFactory,
    private val readingPositionStore: ReadingPositionStore,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val annotationStore: AnnotationStore,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

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

    private val positionSaveCoordinator = PositionSaveCoordinator(
        savePosition = { cfi -> epubRepository.saveReadingPosition(itemId, cfi) },
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
    // Non-null once a matched book's three-peer prerequisites are cached: the unified cycle then
    // replaces the single-peer ABS/Storyteller paths. [threePeerServerId] is the active server
    // (the displayed side) that keys the canonical localUpdatedAt.
    private var threePeer: ThreePeerReaderSyncCoordinator? = null
    private var threePeerServerId: String? = null
    // True after the navigator emits its first locator (the restored position on open).
    // The first emission is not new user progress — the position is already in DB — so we skip
    // the DB write to avoid stomping localUpdatedAt before the initial sync cycle runs.
    private var initialLocatorSeen = false

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys: StateFlow<Boolean> = volumeKeyPreferencesStore.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // The reader's active Server id, resolved once on open. Keys the readaloud resume position (reader
    // space, like the reading position) for both the seed-on-open load and the save-on-close — using
    // one captured id keeps the two symmetric and avoids a per-close getActive() DB read.
    private var readerServerId: String? = null

    // Whether the bottom mini-player / sheet is showing.
    private val _readaloudOpen = MutableStateFlow(false)
    val readaloudOpen: StateFlow<Boolean> = _readaloudOpen

    // Mini-player (false) vs full-height bottom sheet (true).
    private val _readaloudExpanded = MutableStateFlow(false)
    val readaloudExpanded: StateFlow<Boolean> = _readaloudExpanded

    // Mirrors the controller's playback state for the bar/sheet controls.
    val playbackState: StateFlow<ReadaloudController.PlaybackState> = playerCoordinator.state

    // The text fragment currently narrated — drives the synced highlight. Null clears it.
    val activeFragmentRef: StateFlow<String?> = playerCoordinator.activeFragmentRef

    // The track is parsed once on first play and reused.
    private var readaloudTrack: com.riffle.core.domain.ReadaloudTrack? = null

    // fragmentRef → sentence text quote, so the synced highlight can be anchored by text when
    // Readium has stripped the sentence spans from the rendered (ABS) EPUB. Built once, off the
    // rendered EPUB's own spans, the first time readaloud prepares.
    private val _sentenceQuotes = MutableStateFlow<Map<String, com.riffle.core.domain.SentenceQuote>>(emptyMap())
    val sentenceQuotes: StateFlow<Map<String, com.riffle.core.domain.SentenceQuote>> = _sentenceQuotes

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
                if (playerCoordinator.state.value.isPlaying && playerCoordinator.activeFragmentRef.value != null) {
                    pushAudiobookFromReadingPosition()
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
                // Stored lastPosition may be empty or malformed (legacy rows / corrupted writes).
                // Fall back to null so openBook() can open the publication at its default position.
                val locator = result.lastPosition?.takeIf { it.isNotBlank() }?.let {
                    try { Locator.fromJSON(JSONObject(it)) } catch (_: Exception) { null }
                }
                _state.value = ReaderState.Ready(
                    publication = pub,
                    title = item.title,
                    initialLocator = locator,
                )
                // A matched book with cached prerequisites runs the three-peer cycle instead of
                // the single-peer ABS/Storyteller paths; otherwise this is null and nothing changes.
                threePeer = runCatching { threePeerSyncFactory.createIfApplicable(itemId) }.getOrNull()
                threePeerServerId = serverRepository.getActive()?.id

                // Sync immediately while localUpdatedAt is still the genuine stored value —
                // before the navigator restore emits and would stamp localUpdatedAt = now.
                if (threePeer != null) {
                    runThreePeerCycle(locator)
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
            if (threePeer != null) runThreePeerCycle(locator)
            else progressSyncController.sync(itemId, locator.toPayload())
        }
    }

    /**
     * One three-peer reconciliation cycle (ADR 0019). The canonical position is the displayed-EPUB
     * reading position with its stored timestamp; a remote win jumps the reader (including a
     * genuinely-newer audiobook listened on another device, bridged through the bundle), and the
     * winning timestamp is persisted as the canonical localUpdatedAt. Any failure is isolated here.
     *
     * The audiobook is reconciled **inbound-only**: the cycle reads it (so a cross-device listen wins
     * and moves the reader) but never writes it. The live audio clock reaches the audiobook only via
     * the separate audiobook-follow loop (see init), which records its own server timestamp so it
     * can't read back as a newer remote and drive the ebook — the feedback loop that previously
     * erased reading progress.
     */
    private suspend fun runThreePeerCycle(locator: Locator?) {
        val coordinator = threePeer ?: return
        val serverId = threePeerServerId ?: return
        val locJson = (locator ?: lastLocator)?.toJSON()?.toString()
        if (locJson != null) {
            val localUpdatedAt = readingPositionStore.loadLocalUpdatedAt(serverId, itemId)
            val result = runCatching { coordinator.runCycle(locJson, localUpdatedAt) }.getOrNull()
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
     * Responsive audiobook-follow: PATCH only the matched ABS audiobook's currentTime. While a
     * sentence is narrating, it uses the **exact narrated fragment**'s audio time (so the audiobook
     * matches the sentence the user hears, not the top of the page); otherwise it falls back to the
     * reading position through the bundle's SMIL. Never touches the ebook/reading position, so it
     * can't erase or override reading progress. No-op when the book isn't a three-peer match.
     *
     * Closes the inbound feedback loop: the audiobook is reconciled both ways, so without this our own
     * write would read back next cycle as a "newer remote" and drive the ebook. We record the server
     * timestamp ABS returns as the local timestamp, so the read comes back equal (local wins ties),
     * never newer. All responsive push callers (loop/pause/close) go through here.
     */
    private suspend fun pushAudiobookFromReadingPosition() {
        val coordinator = threePeer ?: return
        val serverId = threePeerServerId ?: return
        val locJson = lastLocator?.toJSON()?.toString()
        val fragment = playerCoordinator.activeFragmentRef.value
        val stamp = runCatching {
            if (fragment != null) coordinator.pushAudiobookForFragment(fragment, locJson)
            else locJson?.let { coordinator.pushAudiobookProgress(it) }
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

    fun onPositionChanged(locator: Locator) {
        lastLocator = locator
        _currentLocatorHref.value = locator.href.toString()
        _currentLocatorProgression.value = locator.locations.progression?.toFloat() ?: 0f
        if (!initialLocatorSeen) {
            initialLocatorSeen = true
            return
        }
        viewModelScope.launch {
            positionSaveCoordinator.onChanged(locator.toJSON().toString())
        }
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        initialLocatorSeen = false
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            startPeriodicSync()
        }
    }

    fun onReaderClosed() {
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
        viewModelScope.launch {
            val payload = locator.toPayload()
            positionSaveCoordinator.onClose(locator.toJSON().toString(), payload.ebookProgress)
            if (threePeer != null) runThreePeerCycle(locator)
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

    private suspend fun serverProgressToLocator(serverProgress: ServerProgress): Locator? {
        val pub = publication ?: return null
        val cfi = serverProgress.ebookLocation.takeIf { it.startsWith("epubcfi(") }
        if (cfi != null) {
            val docPath = extractCfiDocPath(cfi)
            val spineIndex = epubCfiToSpineIndex(cfi)
            val link = spineIndex?.let { pub.readingOrder.getOrNull(it) }
            val html = spineIndex?.let { readChapterHtml(it) }
            val chapterProgression = if (docPath != null && html != null) cfiDocPathToProgression(docPath, html) else null
            if (link != null && chapterProgression != null) {
                return try {
                    Locator.fromJSON(
                        JSONObject()
                            .put("href", link.href.toString())
                            .put("type", "application/xhtml+xml")
                            .put("locations", JSONObject().put("progression", chapterProgression))
                    )
                } catch (_: Exception) { null }
            }
        }
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
        epubZip?.close()
        epubZip = null
        // Tear down the audio session so it doesn't outlive the reader (clears the highlight too).
        playerCoordinator.close()
        // Cancel the coordinator's state-collection scope so it isn't leaked past this ViewModel.
        playerCoordinator.dispose()
    }

    private val _currentLocatorHref = MutableStateFlow<String?>(null)
    val currentLocatorHref: StateFlow<String?> = _currentLocatorHref

    private val _currentLocatorProgression = MutableStateFlow(0f)
    val currentLocatorProgression: StateFlow<Float> = _currentLocatorProgression

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
            weightSegmentsByChapterLength(base, spineHrefs, positionCounts)
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

    // Cursor position within the rail (0..1). Active segment + within-chapter progression are
    // mapped through cumulative segment weights so the cursor stays inside the highlighted
    // (active) segment even when segments have unequal widths.
    val railCursorPosition: StateFlow<Float> = combine(
        activeRailSegmentIndex,
        railSegments,
        currentLocatorProgression,
    ) { activeIndex, segments, progression ->
        weightedRailCursorPosition(activeIndex, segments, progression)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

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
        _navigationEvents.trySend(link)
        _tocVisible.value = false
    }

    fun navigateToSegment(segment: RailSegment) {
        val pub = (state.value as? ReaderState.Ready)?.publication ?: return
        val link = pub.tableOfContents.findLinkByHref(segment.href) ?: return
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
        _readaloudExpanded.value = false
        _downloadPromptBytes.value = null
        _readaloudOfflineMessage.value = false
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
        // Remember where we stopped before tearing the session down: the sentence narrating now and
        // the reader page it sits on. Reopening uses these to resume in place (same page) or start at
        // the top of the current page (different page) instead of restarting the chapter. Capture
        // before close() — it nulls the active fragment.
        resumeFragmentRef = playerCoordinator.activeFragmentRef.value
        closeLocator = lastLocator
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
        if (hadFragment) viewModelScope.launch { pushAudiobookFromReadingPosition() }
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

    fun expandPlayer() { _readaloudExpanded.value = true }
    fun collapsePlayer() { _readaloudExpanded.value = false }

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            // Record the audiobook position on pause too (the follow loop is gated on isPlaying, which
            // is about to go false), derived from the reading position via the bundle.
            val hadFragment = playerCoordinator.activeFragmentRef.value != null
            playerCoordinator.pause()
            if (hadFragment) viewModelScope.launch { pushAudiobookFromReadingPosition() }
        } else {
            onPlayTapped()
        }
    }

    fun setSpeed(speed: Float) = playerCoordinator.setSpeed(speed)

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
            playerCoordinator.playFromHere(fragmentRef)
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

    // The exact narrated sentence a server sync placed the reader at (set in runThreePeerCycle on an
    // inbound jump). The next "start readaloud" begins here so it matches the synced audiobook
    // position precisely, instead of the page top. Ignored once the reader leaves that chapter.
    private var pendingStartFragmentRef: String? = null

    private suspend fun ensurePreparedAndPlay(bundle: File) {
        ensureOpened(bundle) ?: return
        if (readaloudStarted) {
            // Resume after a pause: ExoPlayer kept its place, just play.
            playerCoordinator.play()
            return
        }
        readaloudStarted = true

        // Matched (3-peer) book: readaloud starts at the reconciled reading position. There is no
        // separate "first sentence of the page" concept — Play resumes where listening/reading last
        // was; a specific sentence is reached via Play-from-here. (A matched audiobook is optional —
        // when present it's the source of #1 below; without one the reconcile is just ebook↔Storyteller
        // and Play falls through to the local readaloud position.) The start resolves, in order:
        //   1. the exact remote sentence a server sync just placed the reader at (pendingStartFragmentRef,
        //      set in runThreePeerCycle when a remote peer — typically the audiobook — won the reconcile)
        //      — only while still in that chapter, else the reader has moved on and it's stale;
        //   2. the local last-played sentence saved on close (resumeFragmentRef);
        //   3. the sentence the current reading position falls in (fragmentAt via the bundle).
        // Falls back to the reading position's chapter only when nothing narrated anchors it (e.g. front
        // matter); resolveStartClip declines rather than restarting the book.
        threePeer?.let { coordinator ->
            val pending = pendingStartFragmentRef?.takeIf { p ->
                lastLocator?.href?.let { resolveEpubHref(it.toString()) } == resolveEpubHref(p.substringBefore('#'))
            }
            val startFragment = pending
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

        // Storyteller-only readaloud (no 3-peer reconciliation): there is no reconciled position to
        // anchor to, so the page-top probe remains the way to find the page's first sentence on a first
        // play / reopen-elsewhere, with resumeFragmentRef for an in-place reopen.
        val closed = closeLocator
        val resume = resumeFragmentRef
        closeLocator = null
        resumeFragmentRef = null
        val loc = lastLocator
        val plan = ReadaloudResumePlanner.plan(
            isScroll = effectiveFormattingPreferences.value.orientation == ReaderOrientation.Vertical,
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
            readaloudPrepared = true
        }
        return track
    }

    private suspend fun ensureTrack(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        readaloudTrack?.let { return it }
        val track = readaloudAudioRepository.readTrack(audioServerId, audioBookId) ?: return null
        readaloudTrack = track
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
            } catch (e: Throwable) {
                // Never let the highlight-quote parse crash playback; the highlight just stays absent.
                android.util.Log.e("RIFFLE_RA", "buildSentenceQuotes failed", e)
            }
        }
    }

    private fun startStorytellerSync() {
        if (!isStorytellerServer) return
        // Three-peer reconciles Storyteller itself; don't also run the single-peer Storyteller loop.
        if (threePeer != null) return
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
