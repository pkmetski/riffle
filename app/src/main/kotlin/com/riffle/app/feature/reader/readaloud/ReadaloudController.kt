package com.riffle.app.feature.reader.readaloud

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.RecordingLogger
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
open class ReadaloudController @Inject constructor(
    @ApplicationContext private val context: Context?,
    applicationScope: ApplicationScope?,
    private val logger: Logger,
) {
    // Test seam: subclasses that override the pre-warm methods need no real Context (only consulted in
    // [ensureConnected], which fakes never reach). Keeps the controller unit-fakeable without Robolectric.
    protected constructor() : this(null, null, RecordingLogger())
    data class PlaybackState(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val currentAudioSrc: String? = null,
        val positionSec: Double = 0.0,
        // Position on the whole-readaloud concatenated timeline (positionSec above is within-file, what
        // the skip logic needs). Used to hand the listen position to the audiobook player on swipe-up.
        val positionGlobalSec: Double = 0.0,
        val currentChapterIndex: Int = -1,
        val chapterCount: Int = 0,
    )

    // Main.immediate is required for Media3 MediaController calls; the survivable Job tree comes from
    // ApplicationScope. Tests use the protected no-arg constructor and subclasses override every method
    // that launches on this scope, so [applicationScope] is permitted to be null — the fallback mirrors
    // the production SupervisorJob semantics so a future partial-override test fake doesn't get
    // surprised by sibling-cancel behaviour.
    private val scope: CoroutineScope = applicationScope?.scopeOn(Dispatchers.Main.immediate)
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var listenerAttached = false
    private var pollJob: Job? = null
    private var track: ReadaloudTrack? = null
    /** Maps a distinct audio file to its index in the queued playlist. */
    private val audioIndex = LinkedHashMap<String, Int>()
    /** Pre-resolved seek target for the next [playFromSecond] call (set during swipe drag). */
    private var preWarmedPosition: ReadaloudTrack.Position? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                logger.d(LogChannel.Handoff) { "RA.onPlaybackStateChanged state=${player.playbackState} isPlaying=${player.isPlaying}" }
            }
            pushState()
        }

        // Surface ExoPlayer failures instead of swallowing them. A truncated/corrupt bundle (e.g. a
        // download cut short by a full disk) decodes to an error here — previously invisible, it
        // looked like "the highlight shows but there's no sound": the small media-overlay track still
        // parses, but playback never starts. Logged so the cause is greppable in a bug report.
        override fun onPlayerError(error: PlaybackException) {
            logger.e(LogChannel.Readaloud, error) { "playback error code=${error.errorCodeName} src=${controller?.currentMediaItem?.mediaId}" }
        }
    }

    /** Connects (if needed), points the service at [bundle], and queues [track]'s audio files. */
    suspend fun prepare(bundle: File, track: ReadaloudTrack) {
        logger.d(LogChannel.Handoff) { "RA.prepare start (controller already connected=${controller != null})" }
        this.track = track
        SharedBundle.current = bundle
        val t0 = System.currentTimeMillis()
        SharedBundle.streaming = null
        val c = ensureConnected() ?: return
        logger.d(LogChannel.Handoff) { "RA.prepare ensureConnected +${System.currentTimeMillis() - t0}ms" }

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
        logger.d(LogChannel.Handoff) { "RA.prepare setMediaItems+prepare +${System.currentTimeMillis() - t0}ms" }
        pushState()
    }

    /**
     * Streaming counterpart of [prepare] (ADR 0028): points the service at the ABS audio source and
     * queues one item per segment keyed by `audioSrc`, exactly as the bundle path does — so the clip,
     * highlight, skip and chapter machinery is identical. The per-segment ABS URL + clip window are
     * restored from [streaming] in the service's session callback.
     */
    internal suspend fun prepareStreaming(streaming: SharedBundle.Streaming, track: ReadaloudTrack) {
        this.track = track
        SharedBundle.current = null
        SharedBundle.streaming = streaming
        val c = ensureConnected() ?: return

        audioIndex.clear()
        val items = ArrayList<MediaItem>()
        track.clips.map { it.audioSrc }.distinct().forEachIndexed { index, audioSrc ->
            audioIndex[audioSrc] = index
            items += MediaItem.Builder().setMediaId(audioSrc).build()
        }
        c.setMediaItems(items)
        c.prepare()
        pushState()
    }

    fun play() {
        logger.d(LogChannel.Handoff) { "RA.play called" }
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

    /**
     * Releases this readaloud handle WITHOUT stopping the shared [AudioPlayerService] player — used
     * when the audiobook player is taking over the same session (swipe-up to the player). Pauses so
     * narration goes silent immediately, but does NOT stop/clearMediaItems: the audiobook's own
     * setMediaItems replaces the queue, and clearing here would kill the audiobook playback that is
     * about to start. Symmetric to AudiobookController.releaseForHandoff.
     *
     * Keeps the [MediaController] Binder connection alive (ADR 0032) so the incoming side reconnects
     * in ~0 ms instead of paying a full [MediaController.Builder.buildAsync] round-trip each time.
     * Keeps [track] so [preWarmSeek] can still resolve a position if the user drags back.
     */
    fun releaseForHandoff() {
        logger.d(LogChannel.Handoff) { "RA.releaseForHandoff (T0 — audio pausing)" }
        pollJob?.cancel()
        pollJob = null
        controller?.run {
            pause()
            removeListener(listener)
        }
        listenerAttached = false
        preWarmedPosition = null
        _state.value = PlaybackState()
    }

    /**
     * Pre-resolves [globalSec] to a [ReadaloudTrack.Position] during the drag gesture, so
     * [playFromSecond] can skip the SMIL computation at commit time (ADR 0032). No-op when [track]
     * is null (audiobook-only entry with no prior readaloud session this app lifetime).
     */
    fun preWarmSeek(globalSec: Double) {
        preWarmedPosition = track?.seekTarget(globalSec)
    }

    /** Discards any pre-warmed seek target — call when the drag is abandoned. */
    fun cancelPreWarm() {
        preWarmedPosition = null
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
        logger.d(LogChannel.Handoff) { "RA.playFromSecond (preWarmed=${preWarmedPosition != null})" }
        val target = preWarmedPosition ?: track?.seekTarget(globalSec) ?: return
        preWarmedPosition = null
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
        listenerAttached = false
        track = null
        preWarmedPosition = null
        SharedBundle.current = null
        SharedBundle.streaming = null
        _state.value = PlaybackState()
    }

    private fun seekToAudio(audioSrc: String, positionSec: Double) {
        val index = audioIndex[audioSrc] ?: return
        controller?.seekTo(index, (positionSec * 1000).toLong())
    }

    private suspend fun ensureConnected(): MediaController? {
        val c = controller ?: run {
            val ctx = context ?: return null
            val token = SessionToken(ctx, ComponentName(ctx, AudioPlayerService::class.java))
            val future = MediaController.Builder(ctx, token).buildAsync()
            val newC = suspendCancellableCoroutine<MediaController?> { cont ->
                future.addListener({
                    cont.resume(runCatching { future.get() }.getOrNull())
                }, ContextCompat.getMainExecutor(ctx))
            }
            controller = newC
            newC
        }
        if (!listenerAttached && c != null) {
            c.addListener(listener)
            listenerAttached = true
        }
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
            currentChapterIndex = t?.chapterIndexAt(audioSrc, positionSec) ?: -1,
            chapterCount = t?.chapterCount ?: 0,
        )
    }

    companion object {
        const val REWIND_SEC = 15.0
        const val FORWARD_SEC = 30.0
        private const val NEAR_START_SEC = 3.0
        private const val POLL_INTERVAL_MS = 250L
    }
}
