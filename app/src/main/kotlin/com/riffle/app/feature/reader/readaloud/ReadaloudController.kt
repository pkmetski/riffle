package com.riffle.app.feature.reader.readaloud

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
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
 * The available playback speeds are exactly those in the spec: 0.75× / 1× / 1.25× / 1.5× / 2×.
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
        _state.value = PlaybackState(
            connected = c != null,
            isPlaying = c?.isPlaying == true,
            speed = c?.playbackParameters?.speed ?: 1f,
            currentAudioSrc = c?.currentMediaItem?.mediaId,
            positionSec = (c?.currentPosition ?: 0L) / 1000.0,
        )
    }

    companion object {
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val POLL_INTERVAL_MS = 250L
    }
}
