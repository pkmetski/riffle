package com.riffle.app.feature.reader.session

import com.riffle.app.feature.audiobook.AudiobookHandoffState
import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.ReadaloudAudioAnchor
import com.riffle.app.feature.reader.ReadaloudStartAnchor
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.app.playback.NowPlaying
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerSyncOutcome
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePlanner
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudStartPlan
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.resolveEpubHref
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.io.File

/**
 * Owns all Readaloud UI state and session lifecycle for one open book.
 *
 * Lifted from EpubReaderViewModel as part of the VM split (#303). Each sub-task (8.1–8.5) moves
 * a further slice of the readaloud logic here; stub methods carry TODO markers for the slice that
 * fills them in.
 *
 * MUST NOT import android.webkit.* or ContinuousReaderView.
 * Only org.readium.* import allowed: Locator (the position handoff type).
 */
class ReadaloudSession @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    /**
     * Returns a snapshot of the reader's most-recently-reported Locator. Injected as a lambda so
     * the session stays decoupled from PositionOrchestrator (which the VM owns until Task 9).
     * The park-state setters (on pause/close) need this to record which page they stopped on.
     */
    @Assisted private val snapshotLocator: () -> Locator?,
    private val playerCoordinator: PlayerController,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    private val streamingSessionFactory: ReadaloudStreamingSessionFactory,
    private val storytellerSyncController: StorytellerPositionSyncController,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val sidecarStore: ReadaloudSidecarStore,
    private val readingPositionStore: ReadingPositionStore,
    private val readingSyncStore: SyncPositionStore<String>,
    private val audioSyncStore: SyncPositionStore<Double>,
    private val epubRepository: EpubRepository,
    private val progressFlushScope: ProgressFlushScope,
    private val audiobookHandoffState: AudiobookHandoffState,
    private val connectivityObserver: ConnectivityObserver,
    private val nowPlayingStore: NowPlayingStore,
) {

    @AssistedFactory
    interface Factory {
        fun create(
            scope: CoroutineScope,
            snapshotLocator: () -> Locator?,
        ): ReadaloudSession
    }

    // ---- Interval prefs (needed by rewind/forward before bind()) --------------------------------

    private val skipIntervalSec: StateFlow<Double> = listeningPreferencesStore.skipIntervalSeconds
        .map { it.toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS.toDouble())

    private val rewindIntervalSec: StateFlow<Double> = listeningPreferencesStore.rewindIntervalSeconds
        .map { it.toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS.toDouble())

    // ---- State surface --------------------------------------------------------------------------

    internal val _readaloudAvailable = MutableStateFlow(false)
    val readaloudAvailable: StateFlow<Boolean> = _readaloudAvailable

    internal val _readaloudVisible = MutableStateFlow(false)
    val readaloudVisible: StateFlow<Boolean> = _readaloudVisible

    internal val _readaloudOpen = MutableStateFlow(false)
    val readaloudOpen: StateFlow<Boolean> = _readaloudOpen

    /** The ABS audiobook item to switch to when the mini player is swiped up. */
    private val _audiobookItemId = MutableStateFlow<String?>(null)
    val audiobookItemId: StateFlow<String?> = _audiobookItemId

    /** Pass-through delegation from PlayerCoordinator. */
    val playbackState: StateFlow<com.riffle.app.feature.reader.readaloud.ReadaloudController.PlaybackState>
        get() = playerCoordinator.state

    /** The text fragment currently narrated — drives the synced highlight. */
    val activeFragmentRef: StateFlow<String?> get() = playerCoordinator.activeFragmentRef

    /** How far the live position has advanced through the narrated sentence. */
    val narrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?>
        get() = playerCoordinator.narrationProgress

    /** fragmentRef → sentence text quote for highlight anchoring. */
    private val _sentenceQuotes = MutableStateFlow<Map<String, SentenceQuote>>(emptyMap())
    val sentenceQuotes: StateFlow<Map<String, SentenceQuote>> = _sentenceQuotes

    /** span id → bundle chapter href for "Play from here" scoping. */
    private val _sentenceChapters = MutableStateFlow<Map<String, String>>(emptyMap())
    val sentenceChapters: StateFlow<Map<String, String>> = _sentenceChapters

    val readaloudHighlightColor: StateFlow<ReadaloudHighlightColor> =
        readaloudPreferencesStore.preferences
            .map { it.highlightColor }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ReadaloudHighlightColor.BLUE)

    /** Non-null size means "show the download dialog". */
    private val _downloadPromptBytes = MutableStateFlow<Long?>(null)
    val downloadPromptBytes: StateFlow<Long?> = _downloadPromptBytes

    /** Non-null when play can't start — reason shown in-bar. */
    internal val _readaloudBarMessage = MutableStateFlow<String?>(null)
    val readaloudBarMessage: StateFlow<String?> = _readaloudBarMessage

    /** True while a download is running. */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    /** The track stream for chapterTimeRemaining / bookTimeRemaining combines. */
    private val _readaloudTrackFlow = MutableStateFlow<ReadaloudTrack?>(null)
    val readaloudTrackFlow: StateFlow<ReadaloudTrack?> = _readaloudTrackFlow

    /**
     * Carries chapter-href requests to the screen so it can probe the current page-top sentence
     * against the WebView. The screen replies via [onPageTopResolved].
     */
    private val _pageTopProbeChannel = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )
    val pageTopProbeRequests: Flow<String> = _pageTopProbeChannel.receiveAsFlow()

    // ---- Internal mutable state (declared here; logic populated by sub-tasks 8.2–8.5) ----------

    // --- Speed debounce ---
    private var speedSaveJob: Job? = null
    private var pendingSpeed: Float? = null

    // --- Audio settings identity (set by bind()) ---
    internal var audioSettingsIdentity: AudioIdentity = AudioIdentity("", "")
    internal var initialSpeed: Float = AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED

    // --- Book identity (set from bind() in sub-task 8.2/8.5; empty until first bind) ---
    internal var itemId: String = ""
    internal var readerSyncServerId: String? = null

    // --- Audio-source identity (bundle key) — set by VM init coroutine via property setters ---
    internal var audioBookId: String = ""
    internal var audioServerId: String = ""
    // True when the active server is a Storyteller server; drives availability and sync behaviour.
    internal var isStorytellerServer: Boolean = false
    // The reader's active server id, keys readaloud resume position (both seed-on-open and save-on-close).
    internal var readerServerId: String? = null

    // --- Effective formatting preferences provider (needed by ensurePreparedAndPlay resume planner) ---
    // Injected as a lambda so the session doesn't take a hard dep on FormattingSession.
    var effectiveFormattingPreferencesProvider: () -> FormattingPreferences = { FormattingPreferences() }

    // --- Sync / audiobook-follow providers (Option B seam — set once after construction) ---
    // Returning the VM's live vars avoids the need for setters that 8.5 will remove.
    var readerSyncProvider: () -> ReaderSyncCoordinator? = { null }
    var audiobookFollowProvider: () -> AudiobookFollow? = { null }

    // --- Park state (set on pause/close; cleared on navigation) ---
    internal var parkedFragmentRef: String? = null
    internal var parkedLocatorHref: String? = null
    internal var parkedProgression: Double? = null

    // --- Resume / close state (set on closeReadaloud; cleared on next startReadaloud) ---
    internal var closeLocator: Locator? = null
    internal var resumeFragmentRef: String? = null

    // --- Sync / server-jump target ---
    internal var pendingStartFragmentRef: String? = null

    // --- Preparation / streaming state ---
    internal var readaloudPrepared = false
    internal var streamingSession: ReadaloudStreamingSessionFactory.Session? = null
    internal var streamingBuilding = false
    internal var autoPlayWhenPrepared = false
    internal var preparingTimeoutJob: Job? = null
    internal var readaloudStarted = false

    // --- Bundle / quotes state ---
    @Volatile internal var quoteBundle: File? = null
    @Volatile internal var quotesBuildStarted = false

    // --- Background jobs ---
    internal var storytellerSyncJob: Job? = null
    internal var preWarmTrackJob: Job? = null

    // ---- Leaf controls — lifted verbatim from EpubReaderViewModel in sub-task 8.1 ---------------

    /**
     * Toggles play/pause. On pause: records the park state so a subsequent reconcile cycle doesn't
     * regress the audiobook position (ADR 0031) and flushes the position to stores.
     * On play (not currently playing): delegates to [onPlayTapped].
     */
    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            val pausedFragment = playerCoordinator.activeFragmentRef.value
            playerCoordinator.pause()
            if (pausedFragment != null) {
                parkedFragmentRef = pausedFragment
                parkedLocatorHref = snapshotLocator()?.href?.toString()
                parkedProgression = snapshotLocator()?.locations?.progression
            }
            if (pausedFragment != null) progressFlushScope.flush {
                flushReadaloudPositionToStores(pausedFragment)
                pushAudiobookFromReadingPosition(pausedFragment)
            }
        } else {
            onPlayTapped()
        }
    }

    /** Immediately applies [speed] to the player and debounces persistence. */
    fun setSpeed(speed: Float) {
        playerCoordinator.setSpeed(speed)
        initialSpeed = speed
        pendingSpeed = speed
        speedSaveJob?.cancel()
        speedSaveJob = scope.launch {
            delay(SPEED_SAVE_DEBOUNCE_MS)
            if (audioSettingsIdentity.serverId.isEmpty()) return@launch
            audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed)
            pendingSpeed = null
        }
    }

    /**
     * Persist a debounced-but-not-yet-written speed immediately, so the value a user picks just
     * before dismissing the player isn't lost inside the debounce window.
     */
    fun flushPendingSpeed() {
        val speed = pendingSpeed ?: return
        speedSaveJob?.cancel()
        pendingSpeed = null
        if (audioSettingsIdentity.serverId.isEmpty()) return
        progressFlushScope.flush { audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed) }
    }

    /** Seeks backward by the configured rewind interval. */
    fun rewind() = playerCoordinator.skipBy(-rewindIntervalSec.value)

    /** Seeks forward by the configured skip interval. */
    fun forward() = playerCoordinator.skipBy(skipIntervalSec.value)

    /** Moves to the previous chapter in audio-domain. */
    fun previousChapter() = playerCoordinator.previousChapter()

    /** Moves to the next chapter in audio-domain. */
    fun nextChapter() = playerCoordinator.nextChapter()

    /** Clears the download-size prompt (user dismissed or download started). */
    fun dismissDownloadPrompt() {
        _downloadPromptBytes.value = null
    }

    // ---- Stub API surface — filled in by sub-tasks 8.2–8.5 ------------------------------------

    /**
     * Called before PositionOrchestrator.onPositionChanged when the reader position changes.
     * Clears park state when the reader navigates off the parked page (ADR 0031).
     */
    fun onPositionBeforeForward(locator: Locator) {
        if (parkedFragmentRef != null) {
            val movedOffPage = locator.href.toString() != parkedLocatorHref ||
                kotlin.math.abs(
                    (locator.locations.progression ?: 0.0) - (parkedProgression ?: 0.0)
                ) > PARK_PAGE_EPS
            if (movedOffPage) {
                parkedFragmentRef = null
                parkedLocatorHref = null
                parkedProgression = null
            }
        }
    }

    /**
     * Dual-write the counterpart audiobook position locally (ADR 0030). For a matched book, reading
     * is the same activity as listening, so the just-saved reading position is also persisted into
     * the audiobook store — translated through the bundle's SMIL into the audio second, keyed by the
     * audiobook's own ABS item id, and stamped with the reading row's current dirty state.
     * No-op unless matched with an audiobook target and the position is translatable.
     *
     * SAFEGUARD (memory reference_audiobook_position_double_count): the seconds value must come from
     * ReadaloudAudioAnchor.audiobookSeconds / audiobookFollow.secondsForFragment, never from
     * adding startOffsetSec to currentPosition. See mirrorReadingToAudiobook for the canonical
     * call site.
     */
    suspend fun mirrorReadingToAudiobook(canonicalJson: String) {
        val serverId = readerSyncServerId ?: return
        val readerSync = readerSyncProvider()
        val audiobookFollow = audiobookFollowProvider()
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
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
     * Responsive audiobook-follow: PATCH only the matched ABS audiobook's currentTime, keyed by the
     * exact narrated fragment or the current reading position through the bundle SMIL (ADR 0031).
     * No-op when the book isn't a matched-reconciliation book.
     *
     * NOTE: [fragment] must be captured BEFORE the player is torn down — after teardown the live
     * activeFragmentRef is null and a silent fall-back to the page top would be sent to the server.
     */
    suspend fun pushAudiobookFromReadingPosition(fragment: String?) {
        val serverId = readerSyncServerId ?: return
        val coordinator = readerSyncProvider()
        val locJson = snapshotLocator()?.toJSON()?.toString()
        val stamp = runCatching {
            when {
                coordinator != null ->
                    if (fragment != null) coordinator.pushAudiobookForFragment(fragment, locJson)
                    else locJson?.let { coordinator.pushAudiobookProgress(it) }
                fragment != null -> audiobookFollowProvider()?.pushFragment(fragment)
                else -> null
            }
        }.getOrNull() ?: return
        if (stamp > readingPositionStore.loadLocalUpdatedAt(serverId, itemId)) {
            readingPositionStore.updateLocalTimestamp(serverId, itemId, stamp)
        }
    }

    /**
     * Saves where narration stopped for this book, keyed by the reader's (serverId, itemId). Skips
     * when there is no reader page to resume to. Overwrites any previous row so the position persists
     * indefinitely until the next stop — it is not cleared when consumed on resume.
     * Lifted in sub-task 8.3; called by closeReadaloud() and by the VM's persistReadaloudResumePosition.
     */
    suspend fun persistReadaloudResumePosition(locator: Locator?, fragmentRef: String?) {
        val href = locator?.href?.toString() ?: return
        val serverId = readerServerId ?: return
        val progression = locator.locations.progression
        readaloudResumeStore.save(serverId, itemId, ReadaloudResumePosition(href, progression, fragmentRef))
    }

    /**
     * Flush the full readaloud position into local stores on close/pause (ADR 0031): persist the
     * sentence-precise ebook reading position and the local audiobook position (SMIL seconds).
     * Matched-only; no-op without a coordinator or a resolvable sentence.
     *
     * SAFEGUARD (ProgressFlushScope): callers that fire on pause/close must wrap this call inside
     * [progressFlushScope.flush], not viewModelScope.launch, so the write survives teardown.
     */
    suspend fun flushReadaloudPositionToStores(fragmentRef: String?) {
        val serverId = readerSyncServerId ?: return
        if (fragmentRef == null) return
        val sid = fragmentRef.substringAfter('#', "")
        val sentenceJson = com.riffle.app.feature.reader.readaloudLocatorJson(
            fragmentRef, _sentenceQuotes.value[sid]
        ).toString()
        epubRepository.saveReadingPosition(itemId, sentenceJson)
        val readerSync = readerSyncProvider()
        val audiobookFollow = audiobookFollowProvider()
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        val seconds = readerSync?.audioSecondsForFragment(fragmentRef, snapshotLocator()?.toJSON()?.toString())
            ?: audiobookFollow?.secondsForFragment(fragmentRef)
            ?: return
        val snap = readingSyncStore.snapshot(serverId, itemId)
        audioSyncStore.mirror(serverId, audioItemId, seconds, snap.localUpdatedAt, snap.lastSyncedAt)
    }

    /**
     * Opens (or re-opens) the readaloud player for the current book.
     * Pressing the reader's readaloud control plays immediately via [onPlayTapped].
     */
    fun openReadaloud() {
        if (!_readaloudAvailable.value) return
        openReadaloudSession()
        // Pressing the reader's readaloud control plays immediately — no separate Play tap.
        onPlayTapped()
    }

    /**
     * Opens the readaloud session (shows the player, starts the sync loop) WITHOUT auto-playing.
     * "Play from here" uses this instead of [openReadaloud]: it drives its own seek to the selected
     * sentence, and routing through [onPlayTapped]'s resume planner would fire a SECOND, competing
     * seek — to the saved resume position or the page-top fallback — that races the selection seek.
     */
    private fun openReadaloudSession() {
        _readaloudOpen.value = true
        startStorytellerSync()
    }

    /**
     * Closes the readaloud player, recording park / close state and flushing the position.
     *
     * SAFEGUARD (ProgressFlushScope): the position flush uses [progressFlushScope], not [scope],
     * so the write survives teardown when closing readaloud is followed immediately by leaving the
     * book (reference_progress_flush_scope_teardown.md).
     */
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
        closeLocator = snapshotLocator()
        // Park on the stopped sentence so the audiobook isn't re-derived from the page top until the
        // user navigates off this page (ADR 0031). Keyed by the reader page we're parked on.
        parkedFragmentRef = resumeFragmentRef
        parkedLocatorHref = snapshotLocator()?.href?.toString()
        parkedProgression = snapshotLocator()?.locations?.progression
        // Persist the same stopped position so it survives leaving the book / process death.
        val capturedCloseLocator = closeLocator
        val capturedResumeRef = resumeFragmentRef
        val hadFragment = resumeFragmentRef != null
        pendingStartFragmentRef = null
        readaloudPrepared = false
        readaloudStarted = false
        playerCoordinator.close()
        // Use the fragment captured above — close() has nulled the live one.
        // On the flush scope, not scope: closing readaloud is routinely followed by leaving the book
        // at once, which cancels scope and would abort this PATCH mid-write.
        progressFlushScope.flush {
            // Resume-position persisted on the flush scope so it survives scope cancellation
            // when the user leaves the book immediately after closing readaloud
            // (reference_progress_flush_scope_teardown.md).
            persistReadaloudResumePosition(capturedCloseLocator, capturedResumeRef)
            if (hadFragment) {
                flushReadaloudPositionToStores(resumeFragmentRef)
                pushAudiobookFromReadingPosition(resumeFragmentRef)
            }
        }
    }

    /**
     * Play tapped. If a local bundle is present we prepare (if needed) and play. Otherwise: when
     * online, probe the download size and surface the confirm dialog; when offline, surface the
     * "connect to download" message in the bar.
     */
    fun onPlayTapped() {
        _readaloudBarMessage.value = null
        scope.launch {
            // Bundle precedence (ADR 0028): a downloaded bundle is complete and local — prefer it.
            val bundle = readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
            if (bundle != null) {
                ensurePreparedAndPlay(bundle)
                return@launch
            }
            // Streaming (ADR 0028): build from the sidecar prepared ahead of time when the book opened.
            if (ensureStreamingSession() != null) {
                ensurePreparedAndPlay(bundle = null)
                return@launch
            }
            // No bundle and no streaming session yet. For a matched book the sidecar may still be preparing.
            if (audioBookId != itemId) {
                when (sidecarStore.stateOf(audioServerId, audioBookId)) {
                    ReadaloudSidecarStore.State.Preparing -> {
                        _readaloudBarMessage.value = PREPARING_MESSAGE
                        autoPlayWhenPrepared = true
                        preparingTimeoutJob?.cancel()
                        preparingTimeoutJob = scope.launch {
                            delay(PREPARING_SLOW_TIMEOUT_MS)
                            if (autoPlayWhenPrepared) {
                                _readaloudBarMessage.value =
                                    "Taking longer than usual — download it from the book's details to listen offline"
                            }
                        }
                    }
                    else -> {
                        _readaloudBarMessage.value =
                            "Couldn't stream readaloud — download it from the book's details to listen"
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
     * Audiobook→readaloud handoff: open readaloud and start narrating from [globalSec] on the
     * readaloud timeline. Falls back to the normal play path when no bundle is on disk.
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
        scope.launch {
            preWarmTrackJob?.join()  // wait for background SMIL parse to finish before ensureTrack
            ensureOpened(bundle) ?: return@launch
            readaloudStarted = true
            resumeFragmentRef = null
            closeLocator = null
            playerCoordinator.playFromSecond(globalSec)
        }
    }

    /** "Play from here" from the text-selection menu — seek to the clip narrating [fragmentRef]. */
    fun playFromHere(fragmentRef: String) {
        scope.launch {
            val streaming = ensureStreamingSession() != null
            val bundle = if (streaming) null else readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
            if (!streaming && bundle == null) {
                // No source yet — route through the normal play path so the user is told to download first.
                if (!_readaloudOpen.value) openReadaloud() else onPlayTapped()
                return@launch
            }
            // Open the session WITHOUT onPlayTapped()'s resume autoplay.
            if (!_readaloudOpen.value) openReadaloudSession()
            ensureOpened(bundle) ?: return@launch
            readaloudStarted = true
            resumeFragmentRef = null
            closeLocator = null
            // Re-key the ref onto the bundle chapter the selection sits in (ADR 0031 play-from-here).
            val seekRef = readerSyncProvider()?.bundleFragmentRefForSelection(fragmentRef) ?: fragmentRef
            playerCoordinator.playFromHere(seekRef)
        }
    }

    /**
     * Confirms the download prompt and begins downloading the bundle.
     * [wifiOnly]: if true and the current network is metered, refuses to start and surfaces message.
     */
    fun confirmDownloadAudio(wifiOnly: Boolean) {
        _downloadPromptBytes.value = null
        if (wifiOnly && connectivityObserver.isMetered()) {
            _readaloudBarMessage.value = "Connect to download readaloud audio"
            return
        }
        scope.launch {
            _downloadProgress.value = 0f
            val result = readaloudAudioRepository.downloadAudio(audioServerId, audioBookId) { downloaded, total ->
                if (total > 0) _downloadProgress.value = downloaded.toFloat() / total.toFloat()
            }
            _downloadProgress.value = null
            when (result) {
                AudioDownloadResult.Success -> {
                    _readaloudVisible.value = true
                    _readaloudAvailable.value = true
                    readaloudAudioRepository.bundleFile(audioServerId, audioBookId)?.let { ensurePreparedAndPlay(it) }
                }
                AudioDownloadResult.NoBundle -> Unit
                is AudioDownloadResult.NetworkError ->
                    _readaloudBarMessage.value = "Connect to download readaloud audio"
            }
        }
    }

    /**
     * Called when the WebView resolves the first sentence visible on [href]'s current page
     * ([fragmentId]), or null when none could be located. Starts narration there — chapter top when
     * the id is null. Ignored if the player was closed during the (async) probe round-trip.
     */
    fun onPageTopResolved(href: String, fragmentId: String?) {
        if (!_readaloudOpen.value) return
        playerCoordinator.playFromReaderPosition(href, fragmentId)
    }

    /**
     * Swipe-up handoff to the single large audiobook player.
     * Filled in sub-task 8.4.
     */
    fun prepareAudiobookHandoff(): Double = TODO("filled in by sub-task 8.4")

    /**
     * Called when the audiobook overlay is dismissed without a handoff.
     * Filled in sub-task 8.4.
     */
    fun onAudiobookOverlayDismissed(): Unit = TODO("filled in by sub-task 8.4")

    /** Called when the user starts dragging up (reserved for future pre-warm). */
    fun hintAudiobookHandoff(): Unit = Unit

    /** Discard any pre-warm state if the drag was abandoned. */
    fun cancelHandoffHint(): Unit = Unit

    // ---- Accessors for runReaderSyncCycle (stays in VM until sub-task 8.5) ---------------------

    /** Returns the currently parked fragment ref. */
    fun getParkedFragmentRef(): String? = parkedFragmentRef

    /** Clears the close/resume state after a server sync jump. */
    fun clearCloseAndResumeForServerJump() {
        closeLocator = null
        resumeFragmentRef = null
    }

    /** Sets the pending start fragment ref from a server sync cycle. */
    fun setPendingStartFragmentRef(value: String?) {
        pendingStartFragmentRef = value
    }

    // ---- Bind + close -------------------------------------------------------------------------

    /**
     * Binds the session to a specific open book. Must be called before [openReadaloud].
     * The actual implementation moves here in sub-task 8.2; for now it's a stub.
     * Filled in sub-task 8.2.
     */
    fun bind(
        serverId: String,
        itemId: String,
        isStorytellerServer: Boolean,
        audioBookId: String,
        audioServerId: String,
        audioSettingsIdentity: AudioIdentity,
        audiobookItemId: String?,
        effectiveFormattingPreferencesFlow: kotlinx.coroutines.flow.StateFlow<com.riffle.core.domain.FormattingPreferences>,
        currentLocatorFlow: StateFlow<Locator?>,
        readerSyncProvider: () -> com.riffle.app.feature.reader.ReaderSyncCoordinator?,
        audiobookFollowProvider: () -> com.riffle.app.feature.reader.AudiobookFollow?,
        readerSyncServerIdProvider: () -> String?,
    ): Unit = TODO("filled in by sub-task 8.5")

    /**
     * Called when the book is being closed (onCleared / navigating away). Flushes any pending
     * speed write and performs close-side readaloud teardown.
     * Filled in sub-task 8.5.
     */
    fun onBookClosed(): Unit = TODO("filled in by sub-task 8.5")

    // ---- Private helpers lifted in sub-task 8.3 ------------------------------------------------

    private suspend fun ensurePreparedAndPlay(bundle: File?) {
        ensureOpened(bundle) ?: return
        // Record the active readaloud so a media-notification tap reopens this book's reader.
        nowPlayingStore.set(NowPlaying.Readaloud(itemId))
        if (readaloudStarted) {
            // Resume after a pause: rewind by the configured amount then play.
            val rewindSec = rewindOnResumeSec.value
            if (rewindSec > 0) playerCoordinator.skipBy(-rewindSec)
            playerCoordinator.play()
            return
        }
        readaloudStarted = true

        // Reconcile the readaloud start against the LOCAL audiobook position (ADR 0031).
        val localAudioStartFragment: String? = run {
            val sid = readerSyncServerId ?: return@run null
            val readerSync = readerSyncProvider()
            val audiobookFollow = audiobookFollowProvider()
            val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return@run null
            val audioSnap = audioSyncStore.snapshot(sid, audioItemId)
            ReadaloudStartAnchor.fromLocalAudio(
                audioSeconds = audioSnap.position,
                audioUpdatedAt = audioSnap.localUpdatedAt,
                readingUpdatedAt = readingSyncStore.snapshot(sid, itemId).localUpdatedAt,
                fragmentForAudioSeconds = { s -> readerSync?.fragmentForAudioSeconds(s) ?: audiobookFollow?.fragmentForAudioSeconds(s) },
            )
        }

        // Matched book: readaloud starts at the reconciled reading position.
        readerSyncProvider()?.let { coordinator ->
            val lastLoc = snapshotLocator()
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

        // Matched book without the full coordinator (no cross-EPUB index): local listen seeds start.
        if (localAudioStartFragment != null) {
            pendingStartFragmentRef = null
            closeLocator = null
            resumeFragmentRef = null
            playerCoordinator.playFromHere(localAudioStartFragment)
            return
        }

        // Storyteller-only readaloud: page-top probe for first play / reopen-elsewhere.
        val closed = closeLocator
        val resume = resumeFragmentRef
        closeLocator = null
        resumeFragmentRef = null
        val loc = snapshotLocator()
        val plan = ReadaloudResumePlanner.plan(
            isScroll = effectiveFormattingPreferencesProvider().orientation != ReaderOrientation.Horizontal,
            closeHref = closed?.href?.toString(),
            closeProgression = closed?.locations?.progression,
            resumeFragmentRef = resume,
            currentHref = loc?.href?.toString(),
            currentProgression = loc?.locations?.progression,
        )
        when (plan) {
            ReadaloudStartPlan.FromReaderPosition ->
                // First play of this session: start at the first FULL sentence visible on the reader's
                // current page via the page-top probe. For the streaming path where quotes are still
                // building, skip the probe and call playFromReaderPosition directly.
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
            is ReadaloudStartPlan.PageTop -> _pageTopProbeChannel.trySend(plan.href)
        }
    }

    private suspend fun ensureOpened(bundle: File?): ReadaloudTrack? {
        val track = ensureTrack(bundle) ?: return null
        if (!readaloudPrepared) {
            val session = streamingSession
            if (session != null) {
                playerCoordinator.openStreaming(session.streaming, track)
            } else {
                playerCoordinator.open(audioBookId, bundle!!, track)
            }
            playerCoordinator.setSpeed(initialSpeed)
            readaloudPrepared = true
        }
        return track
    }

    internal suspend fun ensureTrack(bundle: File?): ReadaloudTrack? {
        readaloudTrack?.let { return it }
        val session = streamingSession
        val track: ReadaloudTrack
        if (session != null) {
            track = session.track
            quoteBundle = session.sidecarFile
        } else {
            val b = bundle ?: return null
            track = readaloudAudioRepository.readTrack(audioServerId, audioBookId) ?: return null
            quoteBundle = b
        }
        readaloudTrack = track
        return track
    }

    private suspend fun ensureStreamingSession(): ReadaloudStreamingSessionFactory.Session? {
        streamingSession?.let { return it }
        val cached = sidecarStore.cachedFile(audioServerId, audioBookId)
        if (cached == null) return null
        if (streamingBuilding) return null
        streamingBuilding = true
        try {
            streamingSession = runCatching {
                streamingSessionFactory.tryBuild(audioServerId, audioBookId)
            }.getOrNull()
        } finally {
            streamingBuilding = false
        }
        return streamingSession
    }

    /**
     * Extracts per-sentence text quotes from the Storyteller bundle for text-anchored highlights.
     * One-shot: [quotesBuildStarted] prevents double-launch on rapid pause→resume.
     */
    internal fun buildSentenceQuotes(bundle: File) {
        if (quotesBuildStarted) return
        quotesBuildStarted = true
        scope.launch(Dispatchers.IO) {
            try {
                val chapters = com.riffle.core.domain.EpubContentExtractor.extract(bundle)?.chapters
                    ?: return@launch
                _sentenceQuotes.value = com.riffle.core.domain.ReadaloudTextQuotes.build(chapters)
                _sentenceChapters.value = com.riffle.core.domain.ReadaloudTextQuotes.sentenceChapterHrefs(chapters)
            } catch (e: Throwable) {
                android.util.Log.e("RIFFLE_RA", "buildSentenceQuotes failed", e)
            }
        }
    }

    private fun startStorytellerSync() {
        if (!isStorytellerServer) return
        // A matched book runs the canonical reconciliation cycle; don't also run the standalone Storyteller loop.
        if (readerSyncProvider() != null) return
        if (storytellerSyncJob?.isActive == true) return
        storytellerSyncJob = scope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                val locator = snapshotLocator() ?: continue
                when (val outcome = storytellerSyncController.runCycle(itemId, locator.toJSON().toString())) {
                    is StorytellerSyncOutcome.PulledRemote -> {
                        try {
                            val pulled = Locator.fromJSON(JSONObject(outcome.locatorJson))
                            if (pulled != null) storytellerServerLocatorCallback?.invoke(pulled)
                        } catch (_: Exception) { /* malformed remote locator — ignore */ }
                    }
                    StorytellerSyncOutcome.PushedLocal,
                    StorytellerSyncOutcome.InSync,
                    StorytellerSyncOutcome.Offline -> Unit
                }
            }
        }
    }

    /**
     * Callback invoked when Storyteller sync pulls a remote locator — routes it to the
     * PositionOrchestrator in the VM. Set once after construction.
     */
    var storytellerServerLocatorCallback: ((Locator) -> Unit)? = null

    // Interval prefs (rewind on resume — needed by ensurePreparedAndPlay)
    private val rewindOnResumeSec: StateFlow<Double> = listeningPreferencesStore.rewindOnResumeSeconds
        .map { it.toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS.toDouble())

    @Volatile private var readaloudTrack: ReadaloudTrack? = null

    companion object {
        internal const val SYNC_INTERVAL_MS = 30_000L
        internal const val PREPARING_MESSAGE = "Preparing narration…"
        internal const val PREPARING_SLOW_TIMEOUT_MS = 15_000L
        /** Debounce window for persisting a playback-speed change. */
        internal const val SPEED_SAVE_DEBOUNCE_MS = 400L

        /**
         * A reading-position progression change beyond this (or any href change) counts as
         * navigating off the page readaloud was parked on; smaller deltas are settle jitter on
         * the same page (ADR 0031).
         */
        internal const val PARK_PAGE_EPS = 0.001
    }
}

