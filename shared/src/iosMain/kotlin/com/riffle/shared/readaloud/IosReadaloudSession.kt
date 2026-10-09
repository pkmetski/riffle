package com.riffle.shared.readaloud

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS counterpart to Android's `ReadaloudSession` — owns all Readaloud UI state and session
 * lifecycle for one open book.
 *
 * The session is created per reader open (factory pattern) so it carries the book's identity
 * (sourceId/itemId) and tears down cleanly when the reader is closed. It is intentionally leaner
 * than the Android session: the data layer (repositories, stores, DAOs) already exists on iOS;
 * this class only orchestrates playback and surface state.
 *
 * Sentence-quote building (required for automatic page turns when narration crosses a chapter
 * boundary in paginated mode) is deferred to a future PR after the full EPUB HTML pipeline is
 * wired on iOS. In this iteration [sentenceQuotes] is always empty, which means [performAutoFollow]
 * skips the text-search path — audio plays and the highlight decoration tracks the active fragment,
 * but paginated mode does not auto-advance pages on chapter boundaries.
 */
internal class IosReadaloudSession(
    private val sourceId: String,
    private val itemId: String,
    private val controller: IosReadaloudController,
    private val audioRepository: ReadaloudAudioRepository,
    private val bundleReader: ReadaloudBundleReader,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val resumeStore: ReadaloudResumeStore,
    private val dispatchers: DispatcherProvider,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)

    // ── State surface ────────────────────────────────────────────────────────────

    private val _readaloudAvailable = MutableStateFlow(false)
    val readaloudAvailable: StateFlow<Boolean> = _readaloudAvailable

    private val _readaloudOpen = MutableStateFlow(false)
    val readaloudOpen: StateFlow<Boolean> = _readaloudOpen

    /** The SMIL text fragment ref currently narrated — drives the highlight decoration. */
    private val _activeFragmentRef = MutableStateFlow<String?>(null)
    val activeFragmentRef: StateFlow<String?> = _activeFragmentRef

    /**
     * Sentence quotes for text-anchored page-follow. Always empty in this iteration; a future PR
     * populates it by parsing the EPUB chapter HTML via [IosEpubNavigatorBridge.getChapterBytes].
     */
    val sentenceQuotes: StateFlow<Map<String, SentenceQuote>> =
        MutableStateFlow<Map<String, SentenceQuote>>(emptyMap())

    val readaloudHighlightColor: StateFlow<HighlightColor> =
        readaloudPreferencesStore.preferences
            .map { it.highlightColor }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HighlightColor.BLUE)

    /** Non-null size means "show the download dialog". */
    private val _downloadPromptBytes = MutableStateFlow<Long?>(null)
    val downloadPromptBytes: StateFlow<Long?> = _downloadPromptBytes

    /** Non-null while a download is running (0..1). */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    /** Non-null when playback can't start — reason shown in-bar. */
    private val _barMessage = MutableStateFlow<String?>(null)
    val barMessage: StateFlow<String?> = _barMessage

    val playbackState: StateFlow<IosReadaloudController.PlaybackState>
        get() = controller.state

    // ── Internal state ───────────────────────────────────────────────────────────

    private var track: ReadaloudTrack? = null
    private var downloadJob: Job? = null

    init {
        // Track the active fragment ref from the controller's audio position.
        scope.launch {
            controller.state.collect { state ->
                val t = track ?: return@collect
                val clip = state.currentAudioSrc?.let { src ->
                    t.activeClipAt(src, state.positionSec)
                }
                _activeFragmentRef.value = clip?.textFragmentRef
            }
        }
        // Determine availability: has audio when the bundle is on disk.
        _readaloudAvailable.value = audioRepository.isAudioAvailable(sourceId, itemId)
        if (!_readaloudAvailable.value) {
            // Probe for a network bundle size so the download dialog can show the right figure.
            scope.launch {
                val bytes = withContext(dispatchers.io) {
                    audioRepository.probeSizeBytes(sourceId, itemId)
                }
                if (bytes != null) {
                    // Book has Storyteller audio — mark available so the UI shows the Play button.
                    _readaloudAvailable.value = true
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    /** Opens the readaloud mini-player and starts narration if audio is available on disk. */
    suspend fun openReadaloud(resumeFragmentRef: String? = null) {
        _readaloudOpen.value = true
        val bundlePath = bundleReader.bundlePath(sourceId, itemId)
        if (bundlePath == null) {
            // Audio not on disk — show download prompt (or stream if Storyteller supports it).
            val sizeBytes = withContext(dispatchers.io) {
                audioRepository.probeSizeBytes(sourceId, itemId)
            }
            _downloadPromptBytes.value = sizeBytes
            return
        }
        startPlaybackFromBundle(bundlePath, resumeFragmentRef)
    }

    fun closeReadaloud() {
        saveResumePosition()
        controller.pause()
        controller.stop()
        track = null
        _readaloudOpen.value = false
        _activeFragmentRef.value = null
        _barMessage.value = null
        _downloadProgress.value = null
    }

    fun togglePlayPause() {
        if (controller.state.value.isPlaying) controller.pause() else controller.play()
    }

    fun skipForward() = controller.forward()
    fun skipBackward() = controller.rewind()
    fun nextChapter() = controller.nextChapter()
    fun previousChapter() = controller.previousChapter()

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    fun playFromFragment(fragmentRef: String) = controller.playFromFragment(fragmentRef)

    /** Initiates a bundle download. Call after the user confirms the download dialog. */
    fun startDownload(wifiOnly: Boolean) {
        if (downloadJob?.isActive == true) return
        _downloadPromptBytes.value = null
        downloadJob = scope.launch {
            val result = audioRepository.downloadAudio(sourceId, itemId) { downloaded, total ->
                _downloadProgress.value = if (total > 0) downloaded.toFloat() / total else null
            }
            _downloadProgress.value = null
            when (result) {
                is AudioDownloadResult.Success -> {
                    _readaloudAvailable.value = true
                    // Auto-open after download completes.
                    openReadaloud()
                }
                is AudioDownloadResult.NoBundle ->
                    _barMessage.value = "No readaloud audio available"
                is AudioDownloadResult.NetworkError ->
                    _barMessage.value = "Download failed"
            }
        }
    }

    fun dismissDownloadPrompt() {
        _downloadPromptBytes.value = null
        if (!audioRepository.isAudioAvailable(sourceId, itemId)) {
            _readaloudOpen.value = false
        }
    }

    fun onDestroy() {
        controller.stop()
        scope.cancel()
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    private suspend fun startPlaybackFromBundle(bundlePath: String, resumeFragmentRef: String?) {
        val t = withContext(dispatchers.io) {
            bundleReader.readTrack(sourceId, itemId)
        } ?: run {
            _barMessage.value = "No readaloud data in bundle"
            return
        }
        track = t

        // Determine the resume position.
        val savedResume = withContext(dispatchers.io) {
            resumeStore.load(sourceId, itemId)
        }
        val savedFragmentRef = savedResume?.fragmentRef
        val resumeAudioSrc: String?
        val resumeOffsetSec: Double
        when {
            resumeFragmentRef != null -> {
                val clip = t.clipForFragment(resumeFragmentRef)
                resumeAudioSrc = clip?.audioSrc
                resumeOffsetSec = clip?.clipBeginSec ?: 0.0
            }
            savedFragmentRef != null -> {
                val clip = t.clipForFragment(savedFragmentRef)
                resumeAudioSrc = clip?.audioSrc
                resumeOffsetSec = clip?.clipBeginSec ?: 0.0
            }
            else -> {
                resumeAudioSrc = null
                resumeOffsetSec = 0.0
            }
        }

        controller.prepare(bundlePath, t, resumeAudioSrc, resumeOffsetSec)
        controller.play()
        _barMessage.value = null
    }

    private fun saveResumePosition() {
        val fragRef = _activeFragmentRef.value ?: return
        val href = fragRef.substringBefore('#')
        scope.launch {
            resumeStore.save(
                sourceId = sourceId,
                itemId = itemId,
                position = ReadaloudResumePosition(
                    href = href,
                    progression = null,
                    fragmentRef = fragRef,
                ),
            )
        }
    }

    /** Factory so one session is created per reader open. */
    fun interface Factory {
        fun create(sourceId: String, itemId: String): IosReadaloudSession
    }
}
