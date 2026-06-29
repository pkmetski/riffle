package com.riffle.app.feature.reader.session

import com.riffle.app.feature.audiobook.AudiobookHandoffState
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.SyncPositionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    private val _readaloudAvailable = MutableStateFlow(false)
    val readaloudAvailable: StateFlow<Boolean> = _readaloudAvailable

    private val _readaloudVisible = MutableStateFlow(false)
    val readaloudVisible: StateFlow<Boolean> = _readaloudVisible

    private val _readaloudOpen = MutableStateFlow(false)
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
    private val _readaloudBarMessage = MutableStateFlow<String?>(null)
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
     * Filled in sub-task 8.2.
     */
    fun onPositionBeforeForward(locator: Locator): Unit =
        TODO("filled in by sub-task 8.2")

    /**
     * Called from positionSaveCoordinator.savePosition to mirror the ebook CFI onto the linked
     * audiobook position store (dual-write, ADR 0030).
     * Filled in sub-task 8.2.
     */
    fun mirrorReadingToAudiobook(cfi: String): Unit =
        TODO("filled in by sub-task 8.2")

    /**
     * Called from positionSaveCoordinator.savePosition and reconcile cycle to push the translated
     * audiobook second computed from the current reading position.
     * Filled in sub-task 8.2.
     */
    suspend fun pushAudiobookFromReadingPosition(fragmentRef: String?): Unit =
        TODO("filled in by sub-task 8.2")

    /**
     * Persists the readaloud resume position so the next session continues from this sentence.
     * Filled in sub-task 8.2.
     */
    suspend fun persistReadaloudResumePosition(locator: Locator?, fragmentRef: String?): Unit =
        TODO("filled in by sub-task 8.2")

    /**
     * Flush the readaloud position to local stores (room + sync table) for a given fragment.
     * Filled in sub-task 8.2.
     */
    suspend fun flushReadaloudPositionToStores(fragmentRef: String?): Unit =
        TODO("filled in by sub-task 8.2")

    /**
     * Opens (or re-opens) the readaloud player for the current book.
     * Filled in sub-task 8.3.
     */
    fun openReadaloud(): Unit = TODO("filled in by sub-task 8.3")

    /**
     * Closes the readaloud player, recording park / close state and flushing the position.
     * Filled in sub-task 8.3.
     */
    fun closeReadaloud(): Unit = TODO("filled in by sub-task 8.3")

    /**
     * Called when the user taps Play (the first entry point to streaming/bundle resolution).
     * Filled in sub-task 8.3.
     */
    fun onPlayTapped(): Unit = TODO("filled in by sub-task 8.3")

    /**
     * Begins narration from a specific absolute second (e.g. audiobook→readaloud handoff).
     * Filled in sub-task 8.3.
     */
    suspend fun startReadaloudAtSecond(globalSec: Double): Unit =
        TODO("filled in by sub-task 8.3")

    /**
     * Plays from the sentence under the user's text selection ("Play from here").
     * Filled in sub-task 8.3.
     */
    fun playFromHere(fragmentRef: String): Unit = TODO("filled in by sub-task 8.3")

    /**
     * Confirms the download prompt and begins downloading the bundle.
     * Filled in sub-task 8.3.
     */
    fun confirmDownloadAudio(): Unit = TODO("filled in by sub-task 8.3")

    /**
     * Called when the WebView resolves the page-top sentence for a given chapter href.
     * Filled in sub-task 8.3.
     */
    fun onPageTopResolved(href: String, fragmentRef: String?): Unit =
        TODO("filled in by sub-task 8.3")

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
    ): Unit = TODO("filled in by sub-task 8.2")

    /**
     * Called when the book is being closed (onCleared / navigating away). Flushes any pending
     * speed write and performs close-side readaloud teardown.
     * Filled in sub-task 8.5.
     */
    fun onBookClosed(): Unit = TODO("filled in by sub-task 8.5")

    // ---- Private helpers (stubs for later sub-tasks) -------------------------------------------

    @Volatile private var readaloudTrack: ReadaloudTrack? = null

    companion object {
        /** Debounce window for persisting a playback-speed change. */
        internal const val SPEED_SAVE_DEBOUNCE_MS = 400L
    }
}

