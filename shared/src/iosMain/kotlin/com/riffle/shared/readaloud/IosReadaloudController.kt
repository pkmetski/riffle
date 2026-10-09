package com.riffle.shared.readaloud

import com.riffle.core.domain.ReadaloudTrack
import com.riffle.shared.audiobook.BundleAudioExtractor
import com.riffle.shared.library.IosReadaloudHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * iOS counterpart to Android's [com.riffle.app.feature.reader.readaloud.ReadaloudController].
 *
 * Wraps [IosReadaloudBridge] (AVQueuePlayer via Swift) and exposes a [PlaybackState] StateFlow
 * that [IosReadaloudSession] observes to drive the synced sentence highlight and mini-player UI.
 *
 * Audio is queued per **distinct audio file** in the [ReadaloudTrack] — exactly as the Android
 * controller queues Media3 MediaItems. The clips' `audioSrc` fields are zip-entry paths;
 * [BundleAudioExtractor] materialises them as `file://` URLs before handing them to AVQueuePlayer.
 */
internal class IosReadaloudController(
    private val bridge: IosReadaloudBridge,
    private val bundleAudioExtractor: BundleAudioExtractor,
    private val readaloudHandoff: IosReadaloudHandoff,
    mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    data class PlaybackState(
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val currentAudioSrc: String? = null,
        val positionSec: Double = 0.0,
        val positionGlobalSec: Double = 0.0,
        val currentChapterIndex: Int = -1,
        val chapterCount: Int = 0,
    )

    private val scope = mainScope
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var track: ReadaloudTrack? = null
    /** Maps a distinct audio-file zip-entry path to its index in the queued playlist. */
    private val audioIndex = LinkedHashMap<String, Int>()
    /** Inverse of [audioIndex]: index → audioSrc, for converting bridge positions back. */
    private val indexToAudioSrc = ArrayList<String>()

    private var pollJob: Job? = null

    init {
        bridge.setPositionCallback(object : IosReadaloudPositionCallback {
            override fun onPosition(srcIndex: Int, offsetSec: Double) {
                val src = indexToAudioSrc.getOrNull(srcIndex) ?: return
                pushState(src, offsetSec)
            }
        })
        bridge.setPlayingCallback(object : IosReadaloudPlayingCallback {
            override fun onPlaying(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) startPolling() else stopPolling()
            }
        })
    }

    /**
     * Extracts audio files from [bundleZipPath], queues them into AVQueuePlayer, and optionally
     * seeks to a resume position. Does not auto-play.
     *
     * @param resumeAudioSrc The audio-file zip-entry path to resume from, or null for the start.
     * @param resumeOffsetSec The within-file offset to resume from.
     */
    suspend fun prepare(
        bundleZipPath: String,
        track: ReadaloudTrack,
        resumeAudioSrc: String? = null,
        resumeOffsetSec: Double = 0.0,
    ) {
        this.track = track
        readaloudHandoff.setTrack(track)

        val distinctSrcs = track.clips.map { it.audioSrc }.distinct()
        audioIndex.clear()
        indexToAudioSrc.clear()
        distinctSrcs.forEachIndexed { i, src ->
            audioIndex[src] = i
            indexToAudioSrc.add(src)
        }

        val fileUrls = bundleAudioExtractor.extractedTrackUrls(bundleZipPath, distinctSrcs)
        if (fileUrls.isEmpty()) return

        val startIndex = resumeAudioSrc?.let { audioIndex[it] } ?: 0
        val startOffset = if (resumeAudioSrc != null) resumeOffsetSec else 0.0
        bridge.prepareAudioSrcs(fileUrls, startIndex, startOffset)
        pushStateFromBridge()
    }

    fun play() {
        bridge.play()
        startPolling()
    }

    fun pause() {
        bridge.pause()
        stopPolling()
    }

    fun setSpeed(speed: Float) {
        bridge.setSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun skipBy(deltaSec: Double) {
        val s = _state.value
        val target = track?.resolveRelativeSkip(s.currentAudioSrc, s.positionSec, deltaSec) ?: return
        seekToAudio(target.audioSrc, target.positionSec)
    }

    fun skipChapter(forward: Boolean) {
        val s = _state.value
        val clip = track?.resolveChapterSkip(
            s.currentAudioSrc, s.positionSec, forward, NEAR_START_SEC,
        ) ?: return
        seekToAudio(clip.audioSrc, clip.clipBeginSec)
    }

    fun rewind() = skipBy(-REWIND_SEC)
    fun forward() = skipBy(FORWARD_SEC)
    fun previousChapter() = skipChapter(forward = false)
    fun nextChapter() = skipChapter(forward = true)

    fun playFromFragment(fragmentRef: String) {
        val clip = track?.clipForFragment(fragmentRef) ?: return
        seekToAudio(clip.audioSrc, clip.clipBeginSec)
        play()
    }

    fun playFromSecond(globalSec: Double) {
        val preWarmed = readaloudHandoff.consumePreWarmedPosition()
        val target = preWarmed ?: track?.seekTarget(globalSec) ?: return
        seekToAudio(target.audioSrc, target.positionSec)
        play()
    }

    fun releaseForHandoff() {
        pause()
        stopPolling()
        _state.value = PlaybackState()
    }

    fun stop() {
        stopPolling()
        bridge.dispose()
        track = null
        audioIndex.clear()
        indexToAudioSrc.clear()
        readaloudHandoff.setTrack(null)
        _state.value = PlaybackState()
    }

    private fun seekToAudio(audioSrc: String, positionSec: Double) {
        val index = audioIndex[audioSrc] ?: return
        bridge.seekToSrc(index, positionSec)
        pushState(audioSrc, positionSec)
    }

    private fun pushStateFromBridge() {
        val srcIndex = bridge.currentSrcIndex()
        val src = indexToAudioSrc.getOrNull(srcIndex) ?: return
        pushState(src, bridge.currentOffsetSec())
    }

    private fun pushState(audioSrc: String, positionSec: Double) {
        val t = track
        _state.value = PlaybackState(
            isPlaying = bridge.isPlaying(),
            speed = _state.value.speed,
            currentAudioSrc = audioSrc,
            positionSec = positionSec,
            positionGlobalSec = t?.globalPositionOf(audioSrc, positionSec) ?: 0.0,
            currentChapterIndex = t?.chapterIndexAt(audioSrc, positionSec) ?: -1,
            chapterCount = t?.chapterCount ?: 0,
        )
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (bridge.isPlaying()) pushStateFromBridge()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        const val REWIND_SEC = 15.0
        const val FORWARD_SEC = 30.0
        private const val NEAR_START_SEC = 3.0
        private const val POLL_INTERVAL_MS = 250L
    }
}
