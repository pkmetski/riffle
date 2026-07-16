package com.riffle.app.feature.audiobook

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.riffle.app.feature.audio.MediaSessionConnector
import com.riffle.app.feature.reader.readaloud.SharedBundle
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.AudiobookTracks
import com.riffle.core.domain.Clock
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.SystemClock
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing handle to the [AudioPlayerService] for [Audiobook] playback (ADR 0029). Connects via a
 * Media3 [MediaController], queues one [MediaItem] per ABS audio track (streamed directly from ABS
 * over HTTP — the auth token is carried in each track URL), and surfaces a polled [PlaybackState]
 * whose `positionSec` is the **book-absolute** position (across all tracks), the value the audiobook
 * player UI and progress sync use.
 *
 * Playback speed is granular (any 0.05× step in 0.5–3.0×), shared with the Readaloud player; see
 * [com.riffle.app.feature.audio.PlaybackSpeed].
 */
@Singleton
open class AudiobookController @Inject constructor(
    private val connector: MediaSessionConnector?,
    applicationScope: ApplicationScope?,
    dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val clock: Clock,
) {
    // Test seam: a subclass that overrides every member the player touches needs no real connector
    // (it's only consulted in [ensureConnected], which fakes never reach). Keeps the controller
    // unit-fakeable without Robolectric. Unconfined dispatchers — subclasses override every method
    // that launches on the scope; the scope is constructed but never dispatched against.
    protected constructor() : this(null, null, UnconfinedDispatcherProvider, RecordingLogger(), SystemClock)

    data class PlaybackState(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        // Book-absolute position up to which ExoPlayer has buffered ahead of [positionSec].
        // Projected through [AbsolutePositionPlayer.getBufferedPosition], so no offset math here.
        val bufferedSec: Double = 0.0,
    )

    // Main.immediate is required for Media3 MediaController calls; the survivable Job tree comes from
    // ApplicationScope so we don't allocate a sibling SupervisorJob. In tests, subclasses override every
    // method that launches on this scope, so [applicationScope] is permitted to be null — the fallback
    // mirrors the production SupervisorJob semantics so a future partial-override test fake doesn't get
    // surprised by sibling-cancel behaviour.
    private val scope: CoroutineScope = applicationScope?.scopeOn(dispatchers.mainImmediate)
        ?: CoroutineScope(SupervisorJob() + dispatchers.mainImmediate)
    private val _state = MutableStateFlow(PlaybackState())
    open val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
    open val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

    // replay=1 so a STATE_ENDED that fires while no collector is attached — e.g. across an Activity
    // recreation (rotation, theme change) right at end-of-book — is still delivered to the next
    // collector. The VM acts on it by stopping the controller (releasing the session), so a re-emit
    // after that point can't re-trigger; idempotent.
    private val _playbackEnded = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    open val playbackEnded: SharedFlow<Unit> = _playbackEnded.asSharedFlow()
    private var timerJob: Job? = null

    private val controller: MediaController? get() = connector?.controller
    private var pollJob: Job? = null
    private var spans: List<AudiobookTrackSpan> = emptyList()
    private var durationSec: Double = 0.0
    private var prepared = false
    private var wantsToPlay = false
    // True when this controller's current session pointed SharedBundle at a bundle file. Guards stop()
    // so it only releases the bundle it set — never one a live Readaloud session owns (e.g. when this
    // player opened a streaming session, or failed to prepare at all, while Readaloud is still playing).
    private var ownsSharedBundle = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)) {
                logger.d(LogChannel.Handoff) { "AB.onPlaybackStateChanged state=${player.playbackState} isPlaying=${player.isPlaying}" }
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                && player.playbackState == Player.STATE_ENDED) {
                _playbackEnded.tryEmit(Unit)
            }
            maybeStart(player)
            pushState()
        }
        override fun onPlayerError(error: PlaybackException) {
            logger.e(LogChannel.Audiobook, error) { "playback error code=${error.errorCodeName} src=${controller?.currentMediaItem?.mediaId}" }
        }
    }

    /**
     * Connects (if needed) and queues the audiobook's [trackUrls] (tokenised ABS URLs), one per
     * [spans] entry, then seeks to [startAtSec] on the book-absolute timeline.
     */
    open suspend fun prepare(
        trackUrls: List<String>,
        spans: List<AudiobookTrackSpan>,
        durationSec: Double,
        startAtSec: Double,
        localZipFile: File? = null,
        coverUri: String? = null,
    ) {
        logger.d(LogChannel.Handoff) { "AB.prepare start (controller already connected=${controller != null})" }
        val t0 = clock.nowMs()
        this.spans = spans
        this.durationSec = durationSec
        SharedAudiobookContext.spans = spans
        SharedAudiobookContext.totalDurationMs = (durationSec * 1000.0).toLong()
        // Bundle-backed audio: the track mediaIds are zip-entry paths the service reads from this file
        // via SharedBundle (the same channel Readaloud uses). Null for HTTP/file sessions, where the
        // service never consults SharedBundle (so we don't touch it and don't claim ownership).
        ownsSharedBundle = localZipFile != null
        if (localZipFile != null) SharedBundle.current = localZipFile
        val c = ensureConnected() ?: return
        logger.d(LogChannel.Handoff) { "AB.prepare ensureConnected +${clock.nowMs() - t0}ms" }
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .apply { if (coverUri != null) setArtworkUri(android.net.Uri.parse(coverUri)) }
            .build()
        val items = trackUrls.map { url ->
            MediaItem.Builder().setMediaId(url).setUri(url).setMediaMetadata(metadata).build()
        }
        // Seed the start position *into* setMediaItems rather than seeking after prepare(): a seek
        // after prepare() lets ExoPlayer briefly buffer/play the first track from 0 before the seek
        // lands. Passing the resolved track index + offset makes it buffer from the resume point.
        val start = AudiobookTracks.startPositionFor(startAtSec, durationSec, spans)
        c.setMediaItems(items, start.trackIndex, start.offsetMs)
        c.prepare()
        logger.d(LogChannel.Handoff) { "AB.prepare setMediaItems+prepare +${clock.nowMs() - t0}ms" }
        prepared = true
        // If the user pressed play before preparation finished, honour it now — but only once the
        // player is ready (see [ResumePlaybackGate]); otherwise the listener starts it on STATE_READY.
        maybeStart(c)
        pushState()
    }

    /**
     * Establishes the [MediaController] binder connection without touching media items. Call during
     * pre-warm so the first swipe-up pays ~0 ms instead of the full [MediaController.Builder.buildAsync]
     * round-trip (ADR 0032).
     */
    open suspend fun warmBinder() {
        ensureConnected()
    }

    open fun play() {
        // Latch the intent; [maybeStart] / the STATE_READY gate starts playback once the player is
        // buffered at the resume position (see [ResumePlaybackGate]).
        wantsToPlay = true
        controller?.let { if (prepared) maybeStart(it) }
    }

    /** Starts playback iff a play intent is latched and the player has buffered to its position. */
    private fun maybeStart(player: Player) {
        if (ResumePlaybackGate.shouldStart(wantsToPlay, player.playbackState == Player.STATE_READY)) {
            wantsToPlay = false
            player.play()
            startPolling()
        }
    }

    fun pause() {
        timerJob?.cancel()
        timerJob = null
        _sleepTimer.value = SleepTimerMode.None
        wantsToPlay = false
        controller?.pause()
        pollJob?.cancel()
        pollJob = null
        pushState()
    }

    open fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        pushState()
    }

    open fun setSleepTimer(mode: SleepTimerMode) {
        timerJob?.cancel()
        timerJob = null
        _sleepTimer.value = mode
        if (mode is SleepTimerMode.CountDown) {
            timerJob = scope.launch {
                var remaining = mode.remainingMs
                while (remaining > 0L) {
                    _sleepTimer.value = SleepTimerMode.CountDown(remaining)
                    delay(1_000L)
                    remaining -= 1_000L
                }
                fadeAndStop()
            }
        }
        // EndOfChapter: no countdown needed; ViewModel calls triggerSleepNow() on chapter change.
    }

    open fun cancelSleepTimer() {
        timerJob?.cancel()
        timerJob = null
        _sleepTimer.value = SleepTimerMode.None
    }

    // Called by ViewModel when a chapter boundary is crossed in EndOfChapter mode.
    open fun triggerSleepNow() {
        timerJob?.cancel()
        timerJob = scope.launch { fadeAndStop() }
    }

    private suspend fun fadeAndStop() {
        repeat(FADE_STEPS) { i ->
            controller?.setVolume((1f - (i + 1f) / FADE_STEPS).coerceAtLeast(0f))
            delay(FADE_STEP_MS)
        }
        pollJob?.cancel()
        controller?.pause()
        controller?.setVolume(1f)
        _sleepTimer.value = SleepTimerMode.None
    }

    /** Seeks to a book-absolute position, resolving it to the right track + offset. */
    open fun seekTo(absoluteSec: Double) {
        val clamped = absoluteSec.coerceIn(0.0, if (durationSec > 0) durationSec else absoluteSec)
        val index = AudiobookTracks.trackIndexAt(clamped, spans)
        val offset = AudiobookTracks.offsetInTrackSec(clamped, spans)
        controller?.seekTo(index, (offset * 1000).toLong())
        pushState()
    }

    fun skipBy(deltaSec: Double) = seekTo(currentAbsoluteSec() + deltaSec)
    fun rewind() = skipBy(-REWIND_SEC)
    fun forward() = skipBy(FORWARD_SEC)

    // The service wraps ExoPlayer in [AbsolutePositionPlayer], whose `getCurrentPosition` override
    // already projects ExoPlayer's per-track ms into book-absolute ms via [SharedAudiobookContext].
    // That value is what the MediaController reports back here, so this side must NOT add the
    // current track's startOffset again — doing so double-counts every time playback advances past
    // track 0 and inflates the displayed position past durationSec (e.g. 19:56 books reading 27:50+).
    open fun currentAbsoluteSec(): Double {
        val c = controller ?: return 0.0
        return c.currentPosition / 1000.0
    }

    fun stop() {
        cancelSleepTimer()
        pollJob?.cancel()
        pollJob = null
        // Drop any cached end-of-book event so the next book opened on this singleton controller
        // doesn't inherit the previous book's Finished signal at first-subscribe.
        _playbackEnded.resetReplayCache()
        connector?.release()
        spans = emptyList()
        prepared = false
        wantsToPlay = false
        // Release the bundle reference only if THIS session set it (parity with ReadaloudController),
        // never one a still-playing Readaloud owns — media items are already cleared, so nothing
        // restores a zip URI after this.
        if (ownsSharedBundle) SharedBundle.current = null
        ownsSharedBundle = false
        SharedAudiobookContext.spans = emptyList()
        SharedAudiobookContext.totalDurationMs = 0L
        _state.value = PlaybackState()
    }

    /**
     * Releases this audiobook handle WITHOUT stopping the shared [AudioPlayerService] player — used
     * when readaloud is taking over the same session (the audiobook→readaloud swipe). Pauses first so
     * the audiobook goes silent immediately, but does NOT `stop()`/`clearMediaItems()`: readaloud's
     * own `setMediaItems` replaces the queue, and clearing here would kill the readaloud playback that
     * is about to start (the "swipe down pauses readaloud" bug). Leaves the bundle for readaloud.
     *
     * Keeps the [MediaController] Binder connection alive (ADR 0032) so the incoming side reconnects
     * in ~0 ms instead of paying a full [MediaController.Builder.buildAsync] round-trip each time.
     */
    fun releaseForHandoff() {
        logger.d(LogChannel.Handoff) { "AB.releaseForHandoff (T0 — audio pausing)" }
        cancelSleepTimer()
        pollJob?.cancel()
        pollJob = null
        controller?.run {
            setVolume(1f)
            pause()
        }
        connector?.releaseForHandoff()
        spans = emptyList()
        prepared = false
        wantsToPlay = false
        ownsSharedBundle = false
        SharedAudiobookContext.spans = emptyList()
        SharedAudiobookContext.totalDurationMs = 0L
        _state.value = PlaybackState()
    }

    private suspend fun ensureConnected(): MediaController? {
        val c = connector?.ensureConnected() ?: return null
        connector.attachListener(listener)
        pushState()
        return c
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                pushState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pushState() {
        val c = controller
        _state.value = PlaybackState(
            connected = c != null,
            isPlaying = c?.isPlaying == true,
            speed = c?.playbackParameters?.speed ?: 1f,
            positionSec = currentAbsoluteSec(),
            durationSec = durationSec,
            bufferedSec = (c?.bufferedPosition ?: 0L) / 1000.0,
        )
    }

    companion object {
        const val REWIND_SEC = 15.0
        const val FORWARD_SEC = 30.0
        private const val POLL_INTERVAL_MS = 250L
        private const val FADE_STEPS = 50
        private const val FADE_STEP_MS = 100L

        private val UnconfinedDispatcherProvider = object : DispatcherProvider {
            override val main = kotlinx.coroutines.Dispatchers.Unconfined
            override val mainImmediate = kotlinx.coroutines.Dispatchers.Unconfined
            override val io = kotlinx.coroutines.Dispatchers.Unconfined
            override val default = kotlinx.coroutines.Dispatchers.Unconfined
        }
    }
}
