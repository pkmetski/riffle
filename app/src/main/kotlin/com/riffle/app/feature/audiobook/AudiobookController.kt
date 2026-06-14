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
import com.riffle.app.feature.reader.readaloud.SharedBundle
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
import java.io.File
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
open class AudiobookController @Inject constructor(
    @ApplicationContext private val context: Context?,
) {
    // Test seam: a subclass that overrides every member the player touches needs no real Context (it's
    // only consulted in [ensureConnected], which fakes never reach). Keeps the controller unit-fakeable
    // without Robolectric.
    protected constructor() : this(null)

    data class PlaybackState(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    open val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
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
            maybeStart(player)
            pushState()
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e(LOG, "playback error code=${error.errorCodeName} src=${controller?.currentMediaItem?.mediaId}", error)
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
        this.spans = spans
        this.durationSec = durationSec
        // Bundle-backed audio: the track mediaIds are zip-entry paths the service reads from this file
        // via SharedBundle (the same channel Readaloud uses). Null for HTTP/file sessions, where the
        // service never consults SharedBundle (so we don't touch it and don't claim ownership).
        ownsSharedBundle = localZipFile != null
        if (localZipFile != null) SharedBundle.current = localZipFile
        val c = ensureConnected() ?: return
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
        prepared = true
        // If the user pressed play before preparation finished, honour it now — but only once the
        // player is ready (see [ResumePlaybackGate]); otherwise the listener starts it on STATE_READY.
        maybeStart(c)
        pushState()
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

    open fun currentAbsoluteSec(): Double {
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
        wantsToPlay = false
        // Release the bundle reference only if THIS session set it (parity with ReadaloudController),
        // never one a still-playing Readaloud owns — media items are already cleared, so nothing
        // restores a zip URI after this.
        if (ownsSharedBundle) SharedBundle.current = null
        ownsSharedBundle = false
        _state.value = PlaybackState()
    }

    /**
     * Releases this audiobook handle WITHOUT stopping the shared [AudioPlayerService] player — used
     * when readaloud is taking over the same session (the audiobook→readaloud swipe). Pauses first so
     * the audiobook goes silent immediately, but does NOT `stop()`/`clearMediaItems()`: readaloud's
     * own `setMediaItems` replaces the queue, and clearing here would kill the readaloud playback that
     * is about to start (the "swipe down pauses readaloud" bug). Leaves the bundle for readaloud.
     */
    fun releaseForHandoff() {
        pollJob?.cancel()
        pollJob = null
        controller?.run {
            pause()
            removeListener(listener)
            release()
        }
        controller = null
        spans = emptyList()
        prepared = false
        wantsToPlay = false
        ownsSharedBundle = false
        _state.value = PlaybackState()
    }

    private suspend fun ensureConnected(): MediaController? {
        controller?.let { return it }
        val context = this.context ?: return null
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
