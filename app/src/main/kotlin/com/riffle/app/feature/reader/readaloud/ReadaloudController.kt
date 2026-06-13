package com.riffle.app.feature.reader.readaloud

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.riffle.core.domain.ReadaloudTrack
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
 * App-facing handle to the Readaloud [AudioPlayerService]. Connects via a Media3 [MediaController],
 * queues one [MediaItem] per distinct audio file in the [ReadaloudTrack], and surfaces a polled
 * [PlaybackState] the reader observes to drive the synced highlight and auto-page-turn.
 *
 * Playback speed is granular (any 0.05× step in 0.5–3.0×, so 1.4× is reachable), set from the
 * mini-player's shared speed sheet; see [com.riffle.app.feature.audio.PlaybackSpeed].
 */
@Singleton
class ReadaloudController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class PlaybackState(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val currentAudioSrc: String? = null,
        val positionSec: Double = 0.0,
        // The full-screen expanded player renders a global timeline scrubber. positionSec above is the
        // within-file position the skip logic needs; these three are the whole-readaloud timeline the
        // scrubber draws and seeks against.
        val positionGlobalSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val chapterStartsSec: List<Double> = emptyList(),
        val currentChapterIndex: Int = -1,
        val chapterCount: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var track: ReadaloudTrack? = null
    /** Maps a distinct audio file to its index in the queued playlist. */
    private val audioIndex = LinkedHashMap<String, Int>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            pushState()
        }

        // Surface ExoPlayer failures instead of swallowing them. A truncated/corrupt bundle (e.g. a
        // download cut short by a full disk) decodes to an error here — previously invisible, it
        // looked like "the highlight shows but there's no sound": the small media-overlay track still
        // parses, but playback never starts. Logged so the cause is greppable in a bug report.
        override fun onPlayerError(error: PlaybackException) {
            Log.e(LOG, "playback error code=${error.errorCodeName} src=${controller?.currentMediaItem?.mediaId}", error)
        }
    }

    /** Connects (if needed), points the service at [bundle], and queues [track]'s audio files. */
    suspend fun prepare(bundle: File, track: ReadaloudTrack) {
        this.track = track
        SharedBundle.current = bundle
        val c = ensureConnected() ?: return

        audioIndex.clear()
        val items = ArrayList<MediaItem>()
        track.clips.map { it.audioSrc }.distinct().forEachIndexed { index, audioSrc ->
            audioIndex[audioSrc] = index
            items += MediaItem.Builder()
                .setMediaId(audioSrc)
                .setUri(ZipAudioDataSource.uriFor(audioSrc))
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
        pushState()
    }

    fun play() {
        controller?.play()
        startPolling()
    }

    fun pause() {
        controller?.pause()
        // Position is frozen while paused, so stop the 250ms poll; the Player.Listener still pushes
        // state on transitions (incl. the pause itself). Polling resumes on the next play().
        pollJob?.cancel()
        pollJob = null
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        pushState()
    }

    /** Rewinds/forwards along the continuous timeline (negative = rewind), clamped to the readaloud. */
    fun skipBy(deltaSec: Double) {
        val s = _state.value
        val target = track?.resolveRelativeSkip(s.currentAudioSrc, s.positionSec, deltaSec) ?: return
        seekToAudio(target.audioSrc, target.positionSec)
        pushState()
    }

    /** Seeks to an absolute position on the concatenated readaloud timeline (full-player scrubber). */
    fun seekTo(globalSec: Double) {
        val target = track?.seekTarget(globalSec) ?: return
        seekToAudio(target.audioSrc, target.positionSec)
        pushState()
    }

    /** Jumps to the first clip of an adjacent chapter (see [ReadaloudTrack.resolveChapterSkip]). */
    fun skipChapter(forward: Boolean) {
        val s = _state.value
        val clip = track?.resolveChapterSkip(
            s.currentAudioSrc, s.positionSec, forward, NEAR_START_SEC,
        ) ?: return
        seekToAudio(clip.audioSrc, clip.clipBeginSec)
        pushState()
    }

    fun rewind() = skipBy(-REWIND_SEC)
    fun forward() = skipBy(FORWARD_SEC)
    fun previousChapter() = skipChapter(forward = false)
    fun nextChapter() = skipChapter(forward = true)

    /** Seeks to [globalSec] on the concatenated timeline and starts playing (audiobook→readaloud handoff). */
    fun playFromSecond(globalSec: Double) {
        val target = track?.seekTarget(globalSec) ?: return
        seekToAudio(target.audioSrc, target.positionSec)
        play()
    }

    /** Starts playback at the clip narrating [fragmentRef] (the "Play from here" entry point). */
    fun playFromFragment(fragmentRef: String) {
        val clip = track?.clipForFragment(fragmentRef) ?: return
        seekToAudio(clip.audioSrc, clip.clipBeginSec)
        play()
    }

    fun currentAudioSrc(): String? = controller?.currentMediaItem?.mediaId
    fun currentPositionSec(): Double = (controller?.currentPosition ?: 0L) / 1000.0

    /** Stops playback and tears the session down — the synced highlight clears when this is called. */
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
        SharedBundle.current = null
        _state.value = PlaybackState()
    }

    private fun seekToAudio(audioSrc: String, positionSec: Double) {
        val index = audioIndex[audioSrc] ?: return
        controller?.seekTo(index, (positionSec * 1000).toLong())
    }

    private suspend fun ensureConnected(): MediaController? {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, AudioPlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener({
                cont.resume(runCatching { future.get() }.getOrNull())
            }, ContextCompat.getMainExecutor(context))
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
        val t = track
        val audioSrc = c?.currentMediaItem?.mediaId
        val positionSec = (c?.currentPosition ?: 0L) / 1000.0
        _state.value = PlaybackState(
            connected = c != null,
            isPlaying = c?.isPlaying == true,
            speed = c?.playbackParameters?.speed ?: 1f,
            currentAudioSrc = audioSrc,
            positionSec = positionSec,
            positionGlobalSec = t?.globalPositionOf(audioSrc, positionSec) ?: 0.0,
            durationSec = t?.totalDurationSec ?: 0.0,
            chapterStartsSec = t?.chapterStartsSec ?: emptyList(),
            currentChapterIndex = t?.chapterIndexAt(audioSrc, positionSec) ?: -1,
            chapterCount = t?.chapterCount ?: 0,
        )
    }

    companion object {
        const val REWIND_SEC = 15.0
        const val FORWARD_SEC = 30.0
        private const val NEAR_START_SEC = 3.0
        private const val POLL_INTERVAL_MS = 250L
        private const val LOG = "RIFFLE_RA"
    }
}
