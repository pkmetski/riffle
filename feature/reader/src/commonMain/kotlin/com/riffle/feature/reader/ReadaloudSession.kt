package com.riffle.feature.reader

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePlanner
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudStartPlan
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.SentenceSpanReader
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.resolveEpubHref
import com.riffle.core.logging.Logger
import com.riffle.core.models.AudioIdentity
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

/**
 * Owns all Readaloud UI state and session lifecycle for one open book.
 *
 * Lifted from `app/.../reader/session/ReadaloudSession.kt` to `feature:reader` commonMain so the
 * 24 [ReadaloudSessionTest] cases run on iOS and both platforms share one implementation.
 *
 * Android wires [ReadaloudPlayerController] to [com.riffle.app.feature.reader.readaloud.PlayerCoordinator];
 * iOS wires it to [com.riffle.shared.readaloud.IosReadaloudPlayerController].
 *
 * Platform-specific deps that cannot cross the seam are injected as lambdas:
 * - [snapshotPosition]: returns the reader's current position on both platforms.
 * - [onHandoffSignal] / [onHandoffDismiss]: route audiobook-handoff events (passed from
 *   AudiobookHandoffState on Android, matching method on iOS).
 * - [onReadaloudStarted]: records the NowPlaying state (NowPlayingStore on Android).
 */
class ReadaloudSession constructor(
    private val scope: CoroutineScope,
    private val snapshotPosition: () -> ReadaloudPosition?,
    private val playerController: ReadaloudPlayerController,
    private val audioRepository: ReadaloudAudioRepository,
    private val bundleReader: ReadaloudBundleReader,
    private val sidecarDelegate: ReadaloudSidecarDelegate,
    private val storytellerSyncDelegate: StorytellerSyncDelegate,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val readingPositionStore: ReadingPositionStore,
    private val readingSyncStore: SyncPositionStore<String>,
    private val audioSyncStore: SyncPositionStore<Double>,
    private val epubRepository: EpubRepository,
    private val progressFlushScope: ProgressFlushScope,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val extractEpub: suspend (bundlePath: String) -> ExtractedEpub?,
    private val spanReader: SentenceSpanReader,
    private val onHandoffSignal: (absItemId: String, atSec: Double) -> Unit = { _, _ -> },
    private val onHandoffDismiss: (absItemId: String) -> Unit = {},
    private val onReadaloudStarted: (itemId: String) -> Unit = {},
) {

    fun interface Factory {
        fun create(scope: CoroutineScope, snapshotPosition: () -> ReadaloudPosition?): ReadaloudSession
    }

    // ---- Internal helpers -----------------------------------------------------------------------

    internal val parkPolicy = ReadaloudParkPolicy()
    internal val quoteBuilder = ReadaloudQuoteBuilder(scope, dispatchers, logger, extractEpub, spanReader)

    /** The fragment ref readaloud last stopped on, or null when not parked. Used by the reconcile cycle to suppress audio pushback. */
    val parkedFragmentRef: String? get() = parkPolicy.fragmentRef

    init {
        scope.launch {
            playbackState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { isPlaying ->
                    if (isPlaying) quoteBuilder.quoteBundle?.let { quoteBuilder.build(it) }
                    onAudioPlayingChanged?.invoke(isPlaying)
                }
        }
        scope.launch {
            while (true) {
                delay(AUDIO_PUSH_INTERVAL_MS)
                val playingFragment = playerController.activeFragmentRef.value
                if (playerController.state.value.isPlaying && playingFragment != null) {
                    pushAudiobookFromReadingPosition(playingFragment)
                }
            }
        }
    }

    var onAudioPlayingChanged: ((Boolean) -> Unit)? = null

    // ---- Interval prefs ------------------------------------------------------------------------

    private val skipIntervalSec: StateFlow<Double> = listeningPreferencesStore.skipIntervalSeconds
        .map { it.toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS.toDouble())

    private val rewindIntervalSec: StateFlow<Double> = listeningPreferencesStore.rewindIntervalSeconds
        .map { it.toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS.toDouble())

    private val rewindOnResumeSec: StateFlow<Double> = listeningPreferencesStore.rewindOnResumeSeconds
        .map { it.toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS.toDouble())

    // ---- State surface -------------------------------------------------------------------------

    internal val _readaloudAvailable = MutableStateFlow(false)
    val readaloudAvailable: StateFlow<Boolean> = _readaloudAvailable

    internal val _readaloudVisible = MutableStateFlow(false)
    val readaloudVisible: StateFlow<Boolean> = _readaloudVisible

    internal val _readaloudOpen = MutableStateFlow(false)
    val readaloudOpen: StateFlow<Boolean> = _readaloudOpen

    internal val _audiobookItemId = MutableStateFlow<String?>(null)
    val audiobookItemId: StateFlow<String?> = _audiobookItemId

    val playbackState: StateFlow<ReadaloudPlaybackState> get() = playerController.state
    val activeFragmentRef: StateFlow<String?> get() = playerController.activeFragmentRef
    val narrationProgress: StateFlow<ReadaloudNarrationProgress?> get() = playerController.narrationProgress
    val sentenceQuotes: StateFlow<Map<String, SentenceQuote>> get() = quoteBuilder.sentenceQuotes
    val sentenceChapters: StateFlow<Map<String, String>> get() = quoteBuilder.sentenceChapters

    val readaloudHighlightColor: StateFlow<HighlightColor> =
        readaloudPreferencesStore.preferences
            .map { it.highlightColor }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HighlightColor.BLUE)

    private val _downloadPromptBytes = MutableStateFlow<Long?>(null)
    val downloadPromptBytes: StateFlow<Long?> = _downloadPromptBytes

    internal val _readaloudBarMessage = MutableStateFlow<String?>(null)
    val readaloudBarMessage: StateFlow<String?> = _readaloudBarMessage

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _readaloudTrackFlow = MutableStateFlow<ReadaloudTrack?>(null)
    val readaloudTrackFlow: StateFlow<ReadaloudTrack?> = _readaloudTrackFlow

    private val _pageTopProbeChannel = Channel<String>(Channel.CONFLATED)
    val pageTopProbeRequests: Flow<String> = _pageTopProbeChannel.receiveAsFlow()

    // ---- Internal mutable state ----------------------------------------------------------------

    private var speedSaveJob: Job? = null
    private var pendingSpeed: Float? = null

    internal var audioSettingsIdentity: AudioIdentity = AudioIdentity("", "")
    var initialSpeed: Float = AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED

    internal var itemId: String = ""
    internal var readerSyncServerId: String? = null

    internal var audioBookId: String = ""
    internal var audioServerId: String = ""
    internal var isStorytellerService: Boolean = false
    internal var readerServerId: String? = null

    var effectiveFormattingPreferencesProvider: () -> FormattingPreferences =
        { FormattingPreferences() }

    var readerSyncProvider: () -> ReaderSyncCoordinator? = { null }
    var audiobookFollowProvider: () -> AudiobookFollow? = { null }

    internal var closePosition: ReadaloudPosition? = null
    internal var resumeFragmentRef: String? = null
    internal var pendingStartFragmentRef: String? = null

    internal var readaloudPrepared = false
    internal var streamingSession: ReadaloudStreamingSession? = null
    internal var streamingBuilding = false
    internal var autoPlayWhenPrepared = false
    internal var preparingTimeoutJob: Job? = null
    internal var readaloudStarted = false

    private var storytellerSyncJob: Job? = null
    internal var preWarmTrackJob: Job? = null

    // ---- Leaf controls -------------------------------------------------------------------------

    fun pause() {
        if (playbackState.value.isPlaying) togglePlayPause()
    }

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            val pausedFragment = playerController.activeFragmentRef.value
            playerController.pause()
            if (pausedFragment != null) {
                val snap = snapshotPosition()
                parkPolicy.onPause(
                    pausedFragment = pausedFragment,
                    snapshotHref = snap?.href,
                    snapshotProgression = snap?.progression,
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

    fun setSpeed(speed: Float) {
        playerController.setSpeed(speed)
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

    fun flushPendingSpeed() {
        val speed = pendingSpeed ?: return
        speedSaveJob?.cancel()
        pendingSpeed = null
        if (audioSettingsIdentity.sourceId.isEmpty()) return
        progressFlushScope.flush { audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed) }
    }

    fun rewind() = playerController.skipBy(-rewindIntervalSec.value)
    fun forward() = playerController.skipBy(skipIntervalSec.value)
    fun previousChapter() = playerController.previousChapter()
    fun nextChapter() = playerController.nextChapter()
    fun dismissDownloadPrompt() { _downloadPromptBytes.value = null }

    // ---- Position callbacks --------------------------------------------------------------------

    fun onPositionBeforeForward(position: ReadaloudPosition) {
        parkPolicy.onPosition(position.href, position.progression)
    }

    suspend fun mirrorReadingToAudiobook(canonicalJson: String) {
        val sourceId = readerSyncServerId ?: return
        val readerSync = readerSyncProvider()
        val audiobookFollow = audiobookFollowProvider()
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        val seconds = ReadaloudAudioAnchor.audiobookSeconds(
            activeFragment = playerController.activeFragmentRef.value,
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

    suspend fun pushAudiobookFromReadingPosition(fragment: String?) {
        val sourceId = readerSyncServerId ?: return
        val coordinator = readerSyncProvider()
        val locJson = snapshotPosition()?.locatorJson
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

    suspend fun persistReadaloudResumePosition(position: ReadaloudPosition?, fragmentRef: String?) {
        val href = position?.href ?: return
        val sourceId = readerServerId ?: return
        val progression = position.progression
        readaloudResumeStore.save(sourceId, itemId, ReadaloudResumePosition(href, progression, fragmentRef))
    }

    suspend fun flushReadaloudPositionToStores(fragmentRef: String?) {
        val sourceId = readerSyncServerId ?: return
        if (fragmentRef == null) return
        val sid = fragmentRef.substringAfter('#', "")
        val sentenceJson = sentenceLocatorJson(fragmentRef, quoteBuilder.sentenceQuotes.value[sid])
        epubRepository.saveReadingPosition(sourceId, itemId, sentenceJson)
        val readerSync = readerSyncProvider()
        val audiobookFollow = audiobookFollowProvider()
        val audioItemId = readerSync?.audioItemId ?: audiobookFollow?.audioItemId ?: return
        val seconds = readerSync?.audioSecondsForFragment(fragmentRef, snapshotPosition()?.locatorJson)
            ?: audiobookFollow?.secondsForFragment(fragmentRef)
            ?: return
        val snap = readingSyncStore.snapshot(sourceId, itemId)
        audioSyncStore.mirror(sourceId, audioItemId, seconds, snap.localUpdatedAt, snap.lastSyncedAt)
    }

    // ---- Open / close --------------------------------------------------------------------------

    fun openReadaloud() {
        if (!_readaloudAvailable.value) return
        openReadaloudSession()
        onPlayTapped()
    }

    private fun openReadaloudSession() {
        _readaloudOpen.value = true
        startStorytellerSync()
    }

    fun closeReadaloud() {
        _readaloudOpen.value = false
        _downloadPromptBytes.value = null
        _readaloudBarMessage.value = null
        flushPendingSpeed()
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
        resumeFragmentRef = playerController.activeFragmentRef.value
        closePosition = snapshotPosition()
        parkPolicy.onClose(
            resumeFragment = resumeFragmentRef,
            snapshotHref = snapshotPosition()?.href,
            snapshotProgression = snapshotPosition()?.progression,
        )
        val capturedClosePos = closePosition
        val capturedResumeRef = resumeFragmentRef
        val hadFragment = resumeFragmentRef != null
        pendingStartFragmentRef = null
        readaloudPrepared = false
        readaloudStarted = false
        playerController.close()
        progressFlushScope.flush {
            persistReadaloudResumePosition(capturedClosePos, capturedResumeRef)
            if (hadFragment) {
                flushReadaloudPositionToStores(resumeFragmentRef)
                pushAudiobookFromReadingPosition(resumeFragmentRef)
            }
        }
    }

    fun onPlayTapped() {
        _readaloudBarMessage.value = null
        scope.launch {
            val bundlePath = bundleReader.bundlePath(audioServerId, audioBookId)
            if (bundlePath != null) {
                ensurePreparedAndPlay(bundlePath)
                return@launch
            }
            if (ensureStreamingSession() != null) {
                ensurePreparedAndPlay(bundlePath = null)
                return@launch
            }
            if (audioBookId != itemId) {
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
                return@launch
            }
            if (!connectivityObserver.isOnline.value) {
                _readaloudBarMessage.value = "Connect to download readaloud audio"
                return@launch
            }
            _downloadPromptBytes.value = audioRepository.probeSizeBytes(audioServerId, audioBookId) ?: 0L
        }
    }

    fun startReadaloudAtSecond(globalSec: Double) {
        if (!_readaloudAvailable.value) return
        val bundlePath = bundleReader.bundlePath(audioServerId, audioBookId)
        if (bundlePath == null) {
            openReadaloud()
            return
        }
        if (!_readaloudOpen.value) openReadaloudSession()
        readaloudPrepared = false
        scope.launch {
            preWarmTrackJob?.join()
            ensureOpened(bundlePath) ?: return@launch
            readaloudStarted = true
            resumeFragmentRef = null
            closePosition = null
            playerController.playFromSecond(globalSec)
        }
    }

    suspend fun ensureSentenceQuotesReady() {
        if (quoteBuilder.quoteBundle == null) {
            val seed = bundleReader.bundlePath(audioServerId, audioBookId)
                ?: sidecarDelegate.cachedBundlePath(audioServerId, audioBookId)
            if (seed != null) quoteBuilder.quoteBundle = seed
        }
        quoteBuilder.ensureBuilt()
    }

    fun playFromHere(fragmentRef: String) {
        scope.launch {
            val streaming = ensureStreamingSession() != null
            val bundlePath = if (streaming) null else bundleReader.bundlePath(audioServerId, audioBookId)
            if (!streaming && bundlePath == null) {
                if (!_readaloudOpen.value) openReadaloud() else onPlayTapped()
                return@launch
            }
            if (!_readaloudOpen.value) openReadaloudSession()
            ensureOpened(bundlePath) ?: return@launch
            readaloudStarted = true
            resumeFragmentRef = null
            closePosition = null
            val seekRef = readerSyncProvider()?.bundleFragmentRefForSelection(fragmentRef) ?: fragmentRef
            playerController.playFromHere(seekRef)
        }
    }

    fun confirmDownloadAudio(wifiOnly: Boolean) {
        _downloadPromptBytes.value = null
        if (wifiOnly && connectivityObserver.isMetered()) {
            _readaloudBarMessage.value = "Connect to download readaloud audio"
            return
        }
        scope.launch {
            _downloadProgress.value = 0f
            val result = audioRepository.downloadAudio(audioServerId, audioBookId) { downloaded, total ->
                if (total > 0) _downloadProgress.value = downloaded.toFloat() / total.toFloat()
            }
            _downloadProgress.value = null
            when (result) {
                AudioDownloadResult.Success -> {
                    _readaloudVisible.value = true
                    _readaloudAvailable.value = true
                    bundleReader.bundlePath(audioServerId, audioBookId)?.let { ensurePreparedAndPlay(it) }
                }
                AudioDownloadResult.NoBundle -> Unit
                is AudioDownloadResult.NetworkError ->
                    _readaloudBarMessage.value = "Connect to download readaloud audio"
            }
        }
    }

    fun onPageTopResolved(href: String, fragmentId: String?) {
        if (!_readaloudOpen.value) return
        playerController.playFromReaderPosition(href, fragmentId)
    }

    fun prepareAudiobookHandoff(): Double {
        val sec = playbackState.value.positionGlobalSec
        val abId = _audiobookItemId.value
        playerController.releaseForHandoff()
        if (abId != null) onHandoffSignal(abId, sec)
        return sec
    }

    fun onAudiobookOverlayDismissed() {
        readaloudPrepared = false
        val abId = _audiobookItemId.value
        if (abId != null) onHandoffDismiss(abId)
    }

    fun hintAudiobookHandoff(): Unit = Unit
    fun cancelHandoffHint(): Unit = Unit

    fun clearCloseAndResumeForServerJump() {
        closePosition = null
        resumeFragmentRef = null
    }

    fun setPendingStartFragmentRef(value: String?) {
        pendingStartFragmentRef = value
    }

    // ---- Bind ----------------------------------------------------------------------------------

    fun resolveMatchedAbs(audioBookId: String, audioServerId: String, audiobookItemId: String?) {
        this.audioBookId = audioBookId
        this.audioServerId = audioServerId
        _audiobookItemId.value = audiobookItemId
        val isMatchedAbs = audioBookId != itemId
        val bundlePresent = audioRepository.isAudioAvailable(audioServerId, audioBookId)
        val control = readaloudControlState(
            isStoryteller = isStorytellerService,
            isMatchedAbs = isMatchedAbs,
            bundlePresent = bundlePresent,
        )
        _readaloudVisible.value = control.visible
        _readaloudAvailable.value = control.enabled
        bundleReader.bundlePath(audioServerId, audioBookId)?.let { path ->
            launchPreWarmTrack(path)
            if (control.enabled) {
                quoteBuilder.quoteBundle = path
                quoteBuilder.build(path)
            }
        }
        if (isMatchedAbs && !bundlePresent) {
            sidecarDelegate.prepare(audioServerId, audioBookId)
            startSidecarObserver()
        }
    }

    fun bind(
        sourceId: String,
        itemId: String,
        isStorytellerService: Boolean,
        audioBookId: String,
        audioServerId: String,
        audioSettingsIdentity: AudioIdentity,
        audiobookItemId: String?,
        effectiveFormattingPreferencesFlow: StateFlow<FormattingPreferences>,
        readerSyncProvider: () -> ReaderSyncCoordinator?,
        audiobookFollowProvider: () -> AudiobookFollow?,
    ) {
        this.itemId = itemId
        this.readerServerId = sourceId
        this.readerSyncServerId = sourceId
        this.isStorytellerService = isStorytellerService
        this.audioBookId = audioBookId
        this.audioServerId = audioServerId
        this.audioSettingsIdentity = audioSettingsIdentity
        _audiobookItemId.value = audiobookItemId

        this.readerSyncProvider = readerSyncProvider
        this.audiobookFollowProvider = audiobookFollowProvider
        this.effectiveFormattingPreferencesProvider = { effectiveFormattingPreferencesFlow.value }

        val isMatchedAbs = audioBookId != itemId
        val bundlePresent = audioRepository.isAudioAvailable(audioServerId, audioBookId)
        val control = readaloudControlState(
            isStoryteller = isStorytellerService,
            isMatchedAbs = isMatchedAbs,
            bundlePresent = bundlePresent,
        )
        _readaloudVisible.value = control.visible
        _readaloudAvailable.value = control.enabled

        scope.launch {
            readaloudResumeStore.load(sourceId, itemId)?.let { saved ->
                closePosition = saved.toClosePosition()
                resumeFragmentRef = saved.fragmentRef
            }
        }

        bundleReader.bundlePath(audioServerId, audioBookId)?.let { path ->
            launchPreWarmTrack(path)
        }

        if (isMatchedAbs && !bundlePresent) {
            sidecarDelegate.prepare(audioServerId, audioBookId)
            startSidecarObserver()
        }

        if (control.enabled) {
            bundleReader.bundlePath(audioServerId, audioBookId)?.let { path ->
                quoteBuilder.quoteBundle = path
                quoteBuilder.build(path)
            }
        }
    }

    private fun ReadaloudResumePosition.toClosePosition(): ReadaloudPosition? {
        if (href.isEmpty()) return null
        // Build a minimal locator JSON string for the resume position.
        val locJson = buildString {
            append("{\"href\":\"")
            append(href.replace("\"", "\\\""))
            append("\",\"type\":\"application/xhtml+xml\",\"locations\":{")
            if (progression != null) {
                append("\"progression\":")
                append(progression)
            }
            append("}}")
        }
        return ReadaloudPosition(href = href, progression = progression, locatorJson = locJson)
    }

    fun cancelStorytellerSync() {
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
    }

    fun onBookClosed() {
        storytellerSyncJob?.cancel(); storytellerSyncJob = null
        preWarmTrackJob?.cancel(); preWarmTrackJob = null
        speedSaveJob?.cancel(); speedSaveJob = null
        preparingTimeoutJob?.cancel(); preparingTimeoutJob = null
        readaloudPrepared = false
        readaloudStarted = false
        autoPlayWhenPrepared = false
        streamingSession = null
        streamingBuilding = false
        quoteBuilder.reset()
        readaloudTrack = null
        parkPolicy.reset()
        closePosition = null
        resumeFragmentRef = null
        pendingStartFragmentRef = null
        _readaloudOpen.value = false
        _downloadPromptBytes.value = null
        _readaloudBarMessage.value = null
    }

    // ---- Private helpers -----------------------------------------------------------------------

    private suspend fun ensurePreparedAndPlay(bundlePath: String?) {
        ensureOpened(bundlePath) ?: return
        onReadaloudStarted(itemId)
        if (readaloudStarted) {
            val rewindSec = rewindOnResumeSec.value
            if (rewindSec > 0) playerController.skipBy(-rewindSec)
            playerController.play()
            return
        }
        readaloudStarted = true

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
                fragmentForAudioSeconds = { s ->
                    readerSync?.fragmentForAudioSeconds(s) ?: audiobookFollow?.fragmentForAudioSeconds(s)
                },
            )
        }

        readerSyncProvider()?.let { coordinator ->
            val lastPos = snapshotPosition()
            val pending = pendingStartFragmentRef?.takeIf { p ->
                lastPos?.href?.let { resolveEpubHref(it) } == resolveEpubHref(p.substringBefore('#'))
            }
            val currentFragment = lastPos?.locatorJson?.let { coordinator.fragmentForCanonical(it) }
            val resume = resumeFragmentRef?.takeIf { r ->
                currentFragment != null && r.substringBefore('#') == currentFragment.substringBefore('#')
            }
            val startFragment = localAudioStartFragment ?: pending ?: resume ?: currentFragment
            pendingStartFragmentRef = null
            closePosition = null
            resumeFragmentRef = null
            if (startFragment != null) {
                playerController.playFromHere(startFragment)
            } else {
                lastPos?.href?.let { playerController.playFromReaderPosition(it, null) }
            }
            return
        }

        if (localAudioStartFragment != null) {
            pendingStartFragmentRef = null
            closePosition = null
            resumeFragmentRef = null
            playerController.playFromHere(localAudioStartFragment)
            return
        }

        val closed = closePosition
        val resume = resumeFragmentRef
        closePosition = null
        resumeFragmentRef = null
        val loc = snapshotPosition()
        val plan = ReadaloudResumePlanner.plan(
            isScroll = effectiveFormattingPreferencesProvider().orientation != ReaderOrientation.Horizontal,
            closeHref = closed?.href,
            closeProgression = closed?.progression,
            resumeFragmentRef = resume,
            currentHref = loc?.href,
            currentProgression = loc?.progression,
        )
        when (plan) {
            ReadaloudStartPlan.FromReaderPosition ->
                if (loc != null) {
                    if (quoteBuilder.sentenceQuotes.value.isNotEmpty()) {
                        _pageTopProbeChannel.trySend(loc.href)
                    } else {
                        playerController.playFromReaderPosition(loc.href, null)
                    }
                } else {
                    playerController.play()
                }
            is ReadaloudStartPlan.Resume -> playerController.playFromHere(plan.fragmentRef)
            is ReadaloudStartPlan.PageTop -> _pageTopProbeChannel.trySend(plan.href)
        }
    }

    private suspend fun ensureOpened(bundlePath: String?): ReadaloudTrack? {
        val track = ensureTrack(bundlePath) ?: return null
        if (!readaloudPrepared) {
            val session = streamingSession
            if (session != null) {
                playerController.openStreaming(session, track)
            } else {
                playerController.open(audioBookId, bundlePath!!, track)
            }
            playerController.setSpeed(initialSpeed)
            readaloudPrepared = true
        }
        return track
    }

    internal suspend fun ensureTrack(bundlePath: String?): ReadaloudTrack? {
        readaloudTrack?.let { return it }
        val session = streamingSession
        val track: ReadaloudTrack
        if (session != null) {
            track = session.track
            quoteBuilder.quoteBundle = session.bundlePath
        } else {
            bundlePath ?: return null
            track = bundleReader.readTrack(audioServerId, audioBookId) ?: return null
            quoteBuilder.quoteBundle = bundlePath
        }
        readaloudTrack = track
        return track
    }

    private suspend fun ensureStreamingSession(): ReadaloudStreamingSession? {
        streamingSession?.let { return it }
        if (sidecarDelegate.cachedBundlePath(audioServerId, audioBookId) == null) return null
        if (streamingBuilding) return null
        streamingBuilding = true
        try {
            streamingSession = runCatching {
                sidecarDelegate.tryBuildStreamingSession(audioServerId, audioBookId)
            }.getOrNull()
        } finally {
            streamingBuilding = false
        }
        return streamingSession
    }

    fun startSidecarObserver() {
        scope.launch {
            sidecarDelegate.states.collect { byKey ->
                when (byKey[sidecarDelegate.key(audioServerId, audioBookId)]) {
                    ReadaloudSidecarState.Ready -> {
                        sidecarDelegate.cachedBundlePath(audioServerId, audioBookId)?.let { path ->
                            quoteBuilder.quoteBundle = path
                            quoteBuilder.build(path)
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
                    ReadaloudSidecarState.Failed -> {
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

    fun launchPreWarmTrack(bundlePath: String) {
        preWarmTrackJob = scope.launch {
            ensureTrack(bundlePath)
        }
    }

    private fun startStorytellerSync() {
        if (!isStorytellerService) return
        if (readerSyncProvider() != null) return
        if (storytellerSyncJob?.isActive == true) return
        storytellerSyncJob = scope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                val pos = snapshotPosition() ?: continue
                when (val outcome = storytellerSyncDelegate.runCycle(itemId, pos.locatorJson)) {
                    is ReadaloudServerSyncOutcome.PulledRemote ->
                        storytellerServerLocatorCallback?.invoke(outcome.locatorJson)
                    ReadaloudServerSyncOutcome.PushedLocal,
                    ReadaloudServerSyncOutcome.InSync,
                    ReadaloudServerSyncOutcome.Offline -> Unit
                }
            }
        }
    }

    var storytellerServerLocatorCallback: ((String) -> Unit)? = null

    @kotlin.concurrent.Volatile private var readaloudTrack: ReadaloudTrack? = null

    companion object {
        internal const val SYNC_INTERVAL_MS = 30_000L
        internal const val PREPARING_MESSAGE = "Preparing narration…"
        internal const val PREPARING_SLOW_TIMEOUT_MS = 15_000L
        internal const val SPEED_SAVE_DEBOUNCE_MS = 400L
        internal const val AUDIO_PUSH_INTERVAL_MS = 10_000L
    }
}
