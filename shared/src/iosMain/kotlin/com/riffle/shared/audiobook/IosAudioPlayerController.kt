package com.riffle.shared.audiobook

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.models.AudiobookTracks
import com.riffle.feature.player.AudioPlayerInterface
import com.riffle.feature.player.NowPlayingMetadataKey
import com.riffle.feature.player.SkipIntervals
import com.riffle.feature.player.SleepTimerMode
import com.riffle.feature.player.notificationArtistText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * This class owns the **book-absolute timeline**: the bridge only ever speaks
 * `(trackIndex, offsetInTrack)`, and every conversion in either direction goes through the shared
 * [AudiobookTracks] math that Android's `AudiobookController` uses. Keeping the projection here (a)
 * removes the divergent private copy that used to live in Swift and (b) makes the whole
 * absolute-position pipeline testable without AVFoundation.
 *
 * One instance per audiobook player open; created by [IosAudioPlayerBridgeFactory].
 */
class IosAudioPlayerController(
    private val bridge: IosAudioPlayerBridge,
    // Injectable so unit tests can drive the controller without the main run loop; production
    // always uses the default (AVFoundation callbacks arrive on the main thread).
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AudioPlayerInterface {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _state = MutableStateFlow(AudioPlayerInterface.PlaybackState())
    override val state: StateFlow<AudioPlayerInterface.PlaybackState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
    override val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

    private val _playbackEnded = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    override val playbackEnded: SharedFlow<Unit> = _playbackEnded.asSharedFlow()

    private var totalDurationSec: Double = 0.0
    private var spans: List<AudiobookTrackSpan> = emptyList()
    private var timeline: AudiobookTimeline = AudiobookTimeline(durationSec = 0.0)
    private var bookTitle: String = ""
    private var coverUri: String? = null
    private var currentSpeed: Float = 1f
    private var lastNowPlayingKey: NowPlayingMetadataKey = NowPlayingMetadataKey.NONE
    private var sleepJob: kotlinx.coroutines.Job? = null

    init {
        bridge.setPositionCallback(object : IosPositionCallback {
            override fun onPosition(trackIndex: Int, offsetSec: Double) {
                val absolute = AudiobookTracks.absoluteSec(trackIndex, offsetSec, spans)
                _state.value = _state.value.copy(
                    positionSec = absolute,
                    durationSec = totalDurationSec,
                    bufferedSec = absolute + bridge.currentTrackBufferedSec(),
                )
                maybeRefreshNowPlaying(absolute)
            }
        })
        bridge.setPlayingCallback(object : IosPlayingCallback {
            override fun onPlaying(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }
        })
        bridge.setRemoteCommandCallback(object : IosRemoteCommandCallback {
            override fun onSeekAbsolute(positionSec: Double) = seekTo(positionSec)
            override fun onSkip(deltaSec: Double) = skipBy(deltaSec)
            override fun onTrackDelta(delta: Int) {
                if (spans.isEmpty()) return
                val target = (bridge.currentTrackIndex() + delta).coerceIn(0, spans.lastIndex)
                seekTo(spans[target].startOffsetSec)
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
        this.spans = spans
        this.timeline = AudiobookTimeline(durationSec = durationSec, chapters = chapters)
        this.bookTitle = bookTitle.orEmpty()
        this.coverUri = coverUri
        _state.value = AudioPlayerInterface.PlaybackState(
            connected = true,
            durationSec = durationSec,
            positionSec = startAtSec,
            speed = currentSpeed,
        )
        // Seed the resume point *into* the queue rather than queueing from track 0 and seeking:
        // parity with Android's `setMediaItems(items, start.trackIndex, start.offsetMs)`.
        val start = AudiobookTracks.startPositionFor(startAtSec, durationSec, spans)
        bridge.preparePlayer(
            trackUrls = trackUrls,
            startTrackIndex = start.trackIndex,
            startOffsetSec = start.offsetMs / MS_PER_SEC,
        )
        pushNowPlaying(startAtSec)
    }

    override suspend fun warmBinder() = Unit

    override fun play() {
        bridge.play()
    }

    override fun pause() {
        // Android's AudiobookController.pause() clears the sleep timer (an explicit pause retires
        // the "stop playing in N minutes" intent); play() deliberately does not.
        cancelSleepTimerInternal()
        bridge.pause()
    }

    override fun setSkipIntervals(intervals: SkipIntervals) {
        bridge.setSkipIntervals(intervals)
    }

    override fun setSpeed(speed: Float) {
        currentSpeed = speed
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
        val clamped = absoluteSec.coerceIn(
            0.0,
            if (totalDurationSec > 0) totalDurationSec else absoluteSec.coerceAtLeast(0.0),
        )
        bridge.seekToTrack(
            trackIndex = AudiobookTracks.trackIndexAt(clamped, spans),
            offsetSec = AudiobookTracks.offsetInTrackSec(clamped, spans),
        )
        _state.value = _state.value.copy(positionSec = clamped)
    }

    override fun skipBy(deltaSec: Double) {
        val newPos = (currentAbsoluteSec() + deltaSec).coerceAtLeast(0.0)
        seekTo(newPos)
    }

    override fun currentAbsoluteSec(): Double =
        AudiobookTracks.absoluteSec(bridge.currentTrackIndex(), bridge.currentTrackOffsetSec(), spans)

    override fun swapTracksFromIndex(fromIndex: Int, newUrls: List<String>) {
        if (newUrls.isEmpty()) return
        bridge.replaceTracksFrom(fromIndex, newUrls)
    }

    override fun clearEndOfBookCache() = Unit

    override fun stop() {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimerMode.None
        bridge.dispose()
        spans = emptyList()
        timeline = AudiobookTimeline(durationSec = 0.0)
        totalDurationSec = 0.0
        lastNowPlayingKey = NowPlayingMetadataKey.NONE
        _state.value = AudioPlayerInterface.PlaybackState()
        scope.cancel()
    }

    override fun releaseForHandoff() {
        bridge.pause()
    }

    /**
     * Refreshes the lock-screen / Control Centre metadata when the chapter changes or the remaining
     * whole-minute count ticks over — the same cadence Android uses for the media-notification
     * artist line, shared via [NowPlayingMetadataKey].
     */
    private fun maybeRefreshNowPlaying(absoluteSec: Double) {
        if (totalDurationSec <= 0.0) return
        val key = NowPlayingMetadataKey.of(absoluteSec, totalDurationSec, timeline.chapterAt(absoluteSec))
        if (key == lastNowPlayingKey) return
        pushNowPlaying(absoluteSec)
    }

    private fun pushNowPlaying(absoluteSec: Double) {
        lastNowPlayingKey =
            NowPlayingMetadataKey.of(absoluteSec, totalDurationSec, timeline.chapterAt(absoluteSec))
        bridge.setNowPlayingInfo(
            title = bookTitle,
            // Parity with Android, which puts "Chapter 3 · 3h 12m left" in the notification's
            // artist line (MediaMetadata.setArtist) rather than the book's author.
            author = notificationArtistText(
                timeline.chapterAt(absoluteSec),
                NowPlayingMetadataKey.remainingSec(absoluteSec, totalDurationSec),
            ),
            durationSec = totalDurationSec,
            positionSec = absoluteSec,
            coverUrl = coverUri,
        )
    }

    private fun cancelSleepTimerInternal() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = SleepTimerMode.None
    }

    companion object {
        private const val SLEEP_TICK_MS = 1_000L
        private const val MS_PER_SEC = 1000.0
    }
}
