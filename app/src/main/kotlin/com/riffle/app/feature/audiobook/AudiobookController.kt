package com.riffle.app.feature.audiobook

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.riffle.app.feature.reader.readaloud.AudioPlayerService
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.AudiobookTracks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
class AudiobookController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class PlaybackState(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var spans: List<AudiobookTrackSpan> = emptyList()
    private var durationSec: Double = 0.0
    private var prepared = false
    private var playWhenPrepared = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()
        override fun onPlayerError(error: PlaybackException) {
            Log.e(LOG, "playback error code=${error.errorCodeName} src=${controller?.currentMediaItem?.mediaId}", error)
        }
    }

    /**
     * Connects (if needed) and queues the audiobook's [trackUrls] (tokenised ABS URLs), one per
     * [spans] entry, then seeks to [startAtSec] on the book-absolute timeline.
     */
    suspend fun prepare(
        trackUrls: List<String>,
        spans: List<AudiobookTrackSpan>,
        durationSec: Double,
        startAtSec: Double,
    ) {
        this.spans = spans
        this.durationSec = durationSec
        val c = ensureConnected() ?: return
        val items = trackUrls.map { url ->
            MediaItem.Builder().setMediaId(url).setUri(url).build()
        }
        c.setMediaItems(items)
        c.prepare()
        seekTo(startAtSec)
        prepared = true
        // If the user pressed play before preparation finished, honour it now so playback starts as
        // soon as it's pressed rather than being silently dropped.
        if (playWhenPrepared) {
            playWhenPrepared = false
            c.play()
            startPolling()
        }
        pushState()
    }

    fun play() {
        val c = controller
        if (!prepared || c == null) {
            // Not connected/prepared yet — latch the intent; prepare() starts playback when ready.
            playWhenPrepared = true
            return
        }
        c.play()
        startPolling()
    }

    fun pause() {
        controller?.pause()
        pollJob?.cancel()
        pollJob = null
        pushState()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        pushState()
    }

    /** Seeks to a book-absolute position, resolving it to the right track + offset. */
    fun seekTo(absoluteSec: Double) {
        val clamped = absoluteSec.coerceIn(0.0, if (durationSec > 0) durationSec else absoluteSec)
        val index = AudiobookTracks.trackIndexAt(clamped, spans)
        val offset = AudiobookTracks.offsetInTrackSec(clamped, spans)
        controller?.seekTo(index, (offset * 1000).toLong())
        pushState()
    }

    fun skipBy(deltaSec: Double) = seekTo(currentAbsoluteSec() + deltaSec)
    fun rewind() = skipBy(-REWIND_SEC)
    fun forward() = skipBy(FORWARD_SEC)

    fun currentAbsoluteSec(): Double {
        val c = controller ?: return 0.0
        return AudiobookTracks.absoluteSec(c.currentMediaItemIndex, c.currentPosition / 1000.0, spans)
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        controller?.run {
            stop()
            clearMediaItems()
            removeListener(listener)
            release()
        }
        controller = null
        spans = emptyList()
        prepared = false
        playWhenPrepared = false
        _state.value = PlaybackState()
    }

    private suspend fun ensureConnected(): MediaController? {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, AudioPlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
        }
        c?.addListener(listener)
        controller = c
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
        )
    }

    companion object {
        const val REWIND_SEC = 15.0
        const val FORWARD_SEC = 30.0
        private const val POLL_INTERVAL_MS = 250L
        private const val LOG = "RIFFLE_AB"
    }
}
