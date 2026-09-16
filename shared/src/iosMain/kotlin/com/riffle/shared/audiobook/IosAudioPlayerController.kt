package com.riffle.shared.audiobook

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.feature.player.AudioPlayerInterface
import com.riffle.feature.player.SleepTimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Adapts [IosAudioPlayerBridge] (AVQueuePlayer via Swift) to the platform-agnostic
 * [AudioPlayerInterface] consumed by the shared [com.riffle.feature.player.AudiobookPlayerViewModel].
 *
 * One instance per audiobook player open; created by [IosAudioPlayerBridgeFactory].
 */
class IosAudioPlayerController(
    private val bridge: IosAudioPlayerBridge,
) : AudioPlayerInterface {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(AudioPlayerInterface.PlaybackState())
    override val state: StateFlow<AudioPlayerInterface.PlaybackState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
    override val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

    private val _playbackEnded = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    override val playbackEnded: SharedFlow<Unit> = _playbackEnded.asSharedFlow()

    private var totalDurationSec: Double = 0.0
    private var sleepJob: kotlinx.coroutines.Job? = null

    init {
        bridge.setPositionCallback(object : IosPositionCallback {
            override fun onPosition(positionSec: Double) {
                _state.value = _state.value.copy(positionSec = positionSec, durationSec = totalDurationSec)
            }
        })
        bridge.setPlayingCallback(object : IosPlayingCallback {
            override fun onPlaying(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }
        })
        // Only emit playbackEnded on natural end-of-book — not on user pause near the end.
        // The bridge fires this exclusively via AVPlayerItemDidPlayToEndTime for the last track.
        bridge.setEndOfBookCallback(object : IosEndOfBookCallback {
            override fun onEndOfBook() {
                scope.launch { _playbackEnded.emit(Unit) }
            }
        })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun prepare(
        trackUrls: List<String>,
        spans: List<AudiobookTrackSpan>,
        durationSec: Double,
        startAtSec: Double,
        localZipFilePath: String?,
        coverUri: String?,
        bookTitle: String?,
        chapters: List<AudiobookChapter>,
    ) {
        // Flush any stale end-of-book event from a prior session before loading new tracks,
        // mirroring Android AudiobookController.resetReplayCache() before prepare().
        _playbackEnded.resetReplayCache()
        totalDurationSec = durationSec
        _state.value = AudioPlayerInterface.PlaybackState(
            connected = true,
            durationSec = durationSec,
            positionSec = startAtSec,
        )
        val offsets = spans.map { it.startOffsetSec }.toDoubleArray()
        bridge.preparePlayer(
            trackUrls = trackUrls,
            trackStartOffsetsSec = offsets,
            startAtSec = startAtSec,
            totalDurationSec = durationSec,
        )
        if (coverUri != null && bookTitle != null) {
            bridge.setNowPlayingInfo(
                title = bookTitle,
                author = "",
                durationSec = durationSec,
                positionSec = startAtSec,
                coverUrl = coverUri,
            )
        }
    }

    override suspend fun warmBinder() = Unit

    override fun play() {
        cancelSleepTimerInternal()
        bridge.play()
    }

    override fun pause() {
        bridge.pause()
    }

    override fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        // Rate must only be set while playing — save and apply on next play() call.
        // IosAudioPlayerBridgeImpl handles this via pendingRate field.
        bridge.setSpeed(speed)
    }

    override fun setSleepTimer(mode: SleepTimerMode) {
        sleepJob?.cancel()
        _sleepTimer.value = mode
        when (mode) {
            is SleepTimerMode.CountDown -> {
                var remaining = mode.remainingMs
                sleepJob = scope.launch {
                    while (remaining > 0) {
                        delay(SLEEP_TICK_MS)
                        remaining -= SLEEP_TICK_MS
                        if (remaining <= 0) {
                            bridge.pause()
                            _sleepTimer.value = SleepTimerMode.None
                        } else {
                            _sleepTimer.value = SleepTimerMode.CountDown(remaining.coerceAtLeast(0))
                        }
                    }
                }
            }
            is SleepTimerMode.EndOfChapter -> { /* chapter detection is done in the ViewModel */ }
            is SleepTimerMode.None -> { /* already cleared */ }
        }
    }

    override fun cancelSleepTimer() {
        cancelSleepTimerInternal()
    }

    override fun triggerSleepNow() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimerMode.None
        bridge.pause()
    }

    override fun seekTo(absoluteSec: Double) {
        bridge.seekTo(absoluteSec)
        _state.value = _state.value.copy(positionSec = absoluteSec)
    }

    override fun skipBy(deltaSec: Double) {
        val newPos = (_state.value.positionSec + deltaSec).coerceAtLeast(0.0)
        seekTo(newPos)
    }

    override fun currentAbsoluteSec(): Double = bridge.currentPositionSec()

    override fun swapTracksFromIndex(fromIndex: Int, newUrls: List<String>) {
        // No-op on iOS: streaming always streams, no local cache swap path.
    }

    override fun clearEndOfBookCache() = Unit

    override fun stop() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimerMode.None
        bridge.dispose()
        _state.value = AudioPlayerInterface.PlaybackState()
        scope.cancel()
    }

    override fun releaseForHandoff() {
        bridge.pause()
    }

    private fun cancelSleepTimerInternal() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = SleepTimerMode.None
    }

    companion object {
        private const val SLEEP_TICK_MS = 1_000L
    }
}
