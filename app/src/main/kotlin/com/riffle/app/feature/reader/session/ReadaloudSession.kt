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
import com.riffle.app.feature.reader.readaloudControlState
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
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.HighlightColor
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
import com.riffle.core.logging.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {

    @AssistedFactory
    interface Factory {
        fun create(
            scope: CoroutineScope,
            snapshotLocator: () -> Locator?,
        ): ReadaloudSession
    }

    // ---- Observers launched at construction time -----------------------------------------------

    internal val parkPolicy = ReadaloudParkPolicy()
    internal val quoteBuilder = ReadaloudQuoteBuilder(scope, dispatchers, logger)

    init {
        // Build the sentence-quote map when audio starts playing (isPlaying false→true transition).
        // Backstops the matched-ABS case where the bundle may be downloaded later in the session;
        // bind() also seeds a build eagerly when the bundle is already on disk at book-open.
        scope.launch {
            playbackState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { isPlaying ->
                    if (isPlaying) quoteBuilder.quoteBundle?.let { quoteBuilder.build(it) }
                    // Notify the VM of the playing state so it can update ReaderStateHolder.
                    onAudioPlayingChanged?.invoke(isPlaying)
                }
        }
        // Audiobook-follow push loop: while readaloud narrates a sentence, push the audiobook
        // position derived from the current reading position (which tracks the audio) through the
        // bundle SMIL, on a tight cadence so it reaches the server promptly (ADR 0031).
        // Writes only the audiobook item, from a page-derived position — never the ebook.
        scope.launch {
            while (true) {
                delay(AUDIO_PUSH_INTERVAL_MS)
                val playingFragment = playerCoordinator.activeFragmentRef.value
                if (playerCoordinator.state.value.isPlaying && playingFragment != null) {
                    pushAudiobookFromReadingPosition(playingFragment)
                }
            }
        }
    }

    /**
     * Callback invoked on every isPlaying state change so the VM can update [ReaderStateHolder].
     * Set once after construction by the VM.
     */
    var onAudioPlayingChanged: ((Boolean) -> Unit)? = null

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
    internal val _audiobookItemId = MutableStateFlow<String?>(null)
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
    val sentenceQuotes: StateFlow<Map<String, SentenceQuote>> get() = quoteBuilder.sentenceQuotes

    /** span id → bundle chapter href for "Play from here" scoping. */
    val sentenceChapters: StateFlow<Map<String, String>> get() = quoteBuilder.sentenceChapters

    val readaloudHighlightColor: StateFlow<HighlightColor> =
        readaloudPreferencesStore.preferences
            .map { it.highlightColor }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HighlightColor.BLUE)

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

    // --- Background jobs ---
    private var storytellerSyncJob: Job? = null
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
                val snap = snapshotLocator()
                parkPolicy.onPause(
                    pausedFragment = pausedFragment,
                    snapshotHref = snap?.href?.toString(),
                    snapshotProgression = snap?.locations?.progression,
                )
                progressFlushScope.flush {
                    flushReadaloudPositionToStores(pausedFragment)
                    pushAudiobookFromReadingPosition(pausedFragment)
                }
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
            if (audioSettingsIdentity.sourceId.isEmpty()) return@launch
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
        if (audioSettingsIdentity.sourceId.isEmpty()) return
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
        parkPolicy.onPosition(locator.href.toString(), locator.locations.progression)
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
        val sourceId = readerSyncServerId ?: return
        val readerSync = readerSyncProvider()
        val audiobookFollow = audiobookFollowProvider()
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        val seconds = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = playerCoordinator.activeFragmentRef.value,
            readaloudOpen = _readaloudOpen.value,
            parkedFragment = parkPolicy.fragmentRef,
            fragmentSeconds = { f ->
                readerSync?.audioSecondsForFragment(f, fallbackCanonicalJson = null)
                    ?: audiobookFollow?.secondsForFragment(f)
            },
            pageSeconds = { readerSync?.audioSecondsForCanonical(canonicalJson) },
        ) ?: return
        val snap = readingSyncStore.snapshot(sourceId, itemId)
        audioSyncStore.mirror(sourceId, audioItemId, seconds, snap.localUpdatedAt, snap.lastSyncedAt)
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
        val sourceId = readerSyncServerId ?: return
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
        if (stamp > readingPositionStore.loadLocalUpdatedAt(sourceId, itemId)) {
            readingPositionStore.updateLocalTimestamp(sourceId, itemId, stamp)
        }
    }

    /**
     * Saves where narration stopped for this book, keyed by the reader's (sourceId, itemId). Skips
     * when there is no reader page to resume to. Overwrites any previous row so the position persists
     * indefinitely until the next stop — it is not cleared when consumed on resume.
     * Lifted in sub-task 8.3; called by closeReadaloud() and by the VM's persistReadaloudResumePosition.
     */
    suspend fun persistReadaloudResumePosition(locator: Locator?, fragmentRef: String?) {
        val href = locator?.href?.toString() ?: return
        val sourceId = readerServerId ?: return
        val progression = locator.locations.progression
        readaloudResumeStore.save(sourceId, itemId, ReadaloudResumePosition(href, progression, fragmentRef))
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
        val sourceId = readerSyncServerId ?: return
        if (fragmentRef == null) return
        val sid = fragmentRef.substringAfter('#', "")
        val sentenceJson = com.riffle.app.feature.reader.readaloudLocatorJson(
            fragmentRef, quoteBuilder.sentenceQuotes.value[sid]
        ).toString()
        epubRepository.saveReadingPosition(itemId, sentenceJson)
        val readerSync = readerSyncProvider()
        val audiobookFollow = audiobookFollowProvider()
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        val seconds = readerSync?.audioSecondsForFragment(fragmentRef, snapshotLocator()?.toJSON()?.toString())
            ?: audiobookFollow?.secondsForFragment(fragmentRef)
            ?: return
        val snap = readingSyncStore.snapshot(sourceId, itemId)
        audioSyncStore.mirror(sourceId, audioItemId, seconds, snap.localUpdatedAt, snap.lastSyncedAt)
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
        parkPolicy.onClose(
            resumeFragment = resumeFragmentRef,
            snapshotHref = snapshotLocator()?.href?.toString(),
            snapshotProgression = snapshotLocator()?.locations?.progression,
        )
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

    /**
     * Blocks the caller until the sentence-quote map has been built from the SMIL sidecar or
     * bundle — used by the "Play from here" selection handler in EpubReaderScreen so it can
     * resolve the tapped word to a SMIL sentence id, not Readium's HTML anchor.
     *
     * If the sidecar observer hasn't yet seeded [ReadaloudQuoteBuilder.quoteBundle] (because
     * the observer's initial emission raced this call), seed it here from the cached sidecar
     * so [ReadaloudQuoteBuilder.ensureBuilt] has something to build against.
     */
    suspend fun ensureSentenceQuotesReady() {
        if (quoteBuilder.quoteBundle == null) {
            // Prefer the on-disk bundle; fall back to the cached sidecar (streaming path).
            val seed = readaloudAudioRepository.bundleFile(audioServerId, audioBookId)
                ?: sidecarStore.cachedFile(audioServerId, audioBookId)
            if (seed != null) quoteBuilder.quoteBundle = seed
        }
        quoteBuilder.ensureBuilt()
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
     *
     * Captures the current listen second, releases the shared player to the audiobook WITHOUT
     * stopping it (so the audiobook keeps playing through the switch), and returns the second to
     * hand off. The reader stays in the Compose Navigation back stack (stacking model, not swap),
     * so onCleared is NOT called here — no guard needed.
     */
    fun prepareAudiobookHandoff(): Double {
        val sec = playbackState.value.positionGlobalSec
        val abId = _audiobookItemId.value
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

    /** Called when the user starts dragging up (reserved for future pre-warm). */
    fun hintAudiobookHandoff(): Unit = Unit

    /** Discard any pre-warm state if the drag was abandoned. */
    fun cancelHandoffHint(): Unit = Unit

    // ---- Accessors for runReaderSyncCycle (stays in VM until sub-task 8.5) ---------------------

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
     *
     * Consolidates all per-book readaloud wiring that was previously scattered across the VM's
     * init coroutine. After bind() returns, the session is fully operational: availability flags
     * are set, providers are wired, and background per-book work (pre-warm, sidecar observer,
     * sentence-quote build, resume-position seed) has been launched.
     *
     * Option α: called from inside openBook()'s suspending body, BEFORE position.bindBook(),
     * so the session is ready before any position events arrive.
     */
    fun bind(
        sourceId: String,
        itemId: String,
        isStorytellerServer: Boolean,
        audioBookId: String,
        audioServerId: String,
        audioSettingsIdentity: AudioIdentity,
        audiobookItemId: String?,
        effectiveFormattingPreferencesFlow: StateFlow<FormattingPreferences>,
        currentLocatorFlow: StateFlow<Locator?>,
        readerSyncProvider: () -> ReaderSyncCoordinator?,
        audiobookFollowProvider: () -> AudiobookFollow?,
        readerSyncServerIdProvider: () -> String?,
    ) {
        // --- Store per-book identity ---------------------------------------------------------
        this.itemId = itemId
        this.readerServerId = sourceId
        // readerSyncServerId is derived from the VM's readerSyncServerId (same server as sourceId
        // for the ebook side). Wire it now so mirrorReadingToAudiobook is non-null immediately.
        this.readerSyncServerId = sourceId
        this.isStorytellerServer = isStorytellerServer
        this.audioBookId = audioBookId
        this.audioServerId = audioServerId
        this.audioSettingsIdentity = audioSettingsIdentity
        _audiobookItemId.value = audiobookItemId

        // --- Wire providers -----------------------------------------------------------------
        this.readerSyncProvider = readerSyncProvider
        this.audiobookFollowProvider = audiobookFollowProvider
        this.effectiveFormattingPreferencesProvider = { effectiveFormattingPreferencesFlow.value }

        // --- Compute and set availability flags ---------------------------------------------
        val isMatchedAbs = audioBookId != itemId
        val bundlePresent = readaloudAudioRepository.isAudioAvailable(audioServerId, audioBookId)
        val control = readaloudControlState(
            isStoryteller = isStorytellerServer,
            isMatchedAbs = isMatchedAbs,
            bundlePresent = bundlePresent,
        )
        _readaloudVisible.value = control.visible
        _readaloudAvailable.value = control.enabled

        // --- Seed close/resume state from persisted resume store ----------------------------
        // Restores where narration stopped last session so the first Play resumes in place.
        // Uses the ebook reader's sourceId (readerServerId) to key the lookup.
        scope.launch {
            readaloudResumeStore.load(sourceId, itemId)?.let { saved ->
                closeLocator = saved.toCloseLocator()
                resumeFragmentRef = saved.fragmentRef
            }
        }

        // --- Pre-warm the SMIL track parse --------------------------------------------------
        // Delegated to the session (sub-task 8.4 task E) so the session owns the job.
        readaloudAudioRepository.bundleFile(audioServerId, audioBookId)?.let { bundle ->
            launchPreWarmTrack(bundle)
        }

        // --- Sidecar observer for matched ABS books without a downloaded bundle -------------
        if (isMatchedAbs && !bundlePresent) {
            sidecarStore.prepare(audioServerId, audioBookId)
            startSidecarObserver()
        }

        // --- Eagerly build sentence quotes if the bundle is already on disk -----------------
        if (control.enabled) {
            readaloudAudioRepository.bundleFile(audioServerId, audioBookId)?.let { bundle ->
                quoteBuilder.quoteBundle = bundle
                quoteBuilder.build(bundle)
            }
        }
    }

    /**
     * Reconstructs the minimal reader [Locator] the resume planner reads — href + column progression —
     * from a persisted [ReadaloudResumePosition]. Private helper used by [bind].
     */
    private fun ReadaloudResumePosition.toCloseLocator(): Locator? = try {
        val locations = org.json.JSONObject().also { obj -> progression?.let { obj.put("progression", it) } }
        Locator.fromJSON(
            org.json.JSONObject()
                .put("href", href)
                .put("type", "application/xhtml+xml")
                .put("locations", locations)
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Cancel the Storyteller sidecar polling job. Called from the VM's `onReaderClosed` lifecycle
     * hook (reader backgrounded). The session retains the right to recreate the job on its next
     * `bind()`. Internal job state stays private to the session.
     */
    fun cancelStorytellerSync() {
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
    }

    /**
     * Called when the book is being closed (onCleared / navigating away). Cancels all session-owned
     * background jobs, clears transient playback state, and resets to closed defaults. The VM's
     * onCleared() calls this after its own teardown.
     */
    fun onBookClosed() {
        // Cancel all background jobs owned by the session.
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
        preWarmTrackJob?.cancel()
        preWarmTrackJob = null
        speedSaveJob?.cancel()
        speedSaveJob = null
        preparingTimeoutJob?.cancel()
        preparingTimeoutJob = null
        // Reset transient playback and prep state so a re-bind on next book open starts clean.
        readaloudPrepared = false
        readaloudStarted = false
        autoPlayWhenPrepared = false
        streamingSession = null
        streamingBuilding = false
        quoteBuilder.reset()
        readaloudTrack = null
        parkPolicy.reset()
        closeLocator = null
        resumeFragmentRef = null
        pendingStartFragmentRef = null
        _readaloudOpen.value = false
        _downloadPromptBytes.value = null
        _readaloudBarMessage.value = null
    }

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
                    if (quoteBuilder.sentenceQuotes.value.isNotEmpty()) {
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
            quoteBuilder.quoteBundle = session.sidecarFile
        } else {
            val b = bundle ?: return null
            track = readaloudAudioRepository.readTrack(audioServerId, audioBookId) ?: return null
            quoteBuilder.quoteBundle = b
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
     * Starts observing the sidecar store for [audioServerId]/[audioBookId] and reacts to
     * [ReadaloudSidecarStore.State.Ready] / [ReadaloudSidecarStore.State.Failed].
     *
     * Called by the VM's init coroutine for matched ABS books where no bundle is on disk (ADR 0028).
     * Moving the observer here folds the VM's inline `viewModelScope.launch { sidecarStore.states… }`
     * into the session so the session is the sole writer of `_readaloudBarMessage` (8.4 task D).
     */
    fun startSidecarObserver() {
        scope.launch {
            sidecarStore.states.collect { byKey ->
                when (byKey[sidecarStore.key(audioServerId, audioBookId)]) {
                    ReadaloudSidecarStore.State.Ready -> {
                        // The sidecar stands in for the bundle for the synced-highlight text quotes
                        // (ADR 0028): build them the moment it's cached, through the SAME
                        // quoteBuilder path the on-disk bundle uses in bind().
                        sidecarStore.cachedFile(audioServerId, audioBookId)?.let { sidecar ->
                            quoteBuilder.quoteBundle = sidecar
                            quoteBuilder.build(sidecar)
                        }
                        if (autoPlayWhenPrepared) {
                            preparingTimeoutJob?.cancel()
                            preparingTimeoutJob = null
                            autoPlayWhenPrepared = false
                            if (_readaloudBarMessage.value == PREPARING_MESSAGE) {
                                _readaloudBarMessage.value = null
                            }
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

    /**
     * Pre-warms the SMIL track parse so the first audiobook→readaloud swipe-down skips the
     * ~1.5s [ReadaloudAudioRepository.readTrack] cost (parses every .smil in the bundle).
     * Stores the result in [readaloudTrack] via [ensureTrack]; [startReadaloudAtSecond] joins the
     * job before calling [ensureOpened] so the track is available synchronously.
     *
     * Replaces the `readaloud.preWarmTrackJob = viewModelScope.launch { ... }` pattern where the VM
     * launched on its own scope and stored the job via an internal field. The session now owns both.
     */
    fun launchPreWarmTrack(bundle: java.io.File) {
        preWarmTrackJob = scope.launch {
            ensureTrack(bundle)
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
        /** Cadence for pushing the audiobook position derived from the narrated reading position (ADR 0031). */
        internal const val AUDIO_PUSH_INTERVAL_MS = 10_000L

    }
}

