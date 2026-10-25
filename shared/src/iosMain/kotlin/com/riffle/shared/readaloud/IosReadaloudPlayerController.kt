package com.riffle.shared.readaloud

import com.riffle.core.domain.ReadaloudTrack
import com.riffle.feature.reader.ReadaloudNarrationProgress
import com.riffle.feature.reader.ReadaloudPlaybackState
import com.riffle.feature.reader.ReadaloudPlayerController
import com.riffle.feature.reader.ReadaloudStreamingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts [IosReadaloudController] to the platform-neutral [ReadaloudPlayerController] interface
 * so the shared [com.riffle.feature.reader.ReadaloudSession] can drive iOS audio without knowing
 * about iOS-specific AVFoundation internals.
 *
 * Streaming sessions are not yet supported on iOS — [openStreaming] is a no-op.
 */
internal class IosReadaloudPlayerController(
    private val controller: IosReadaloudController,
    scope: CoroutineScope,
) : ReadaloudPlayerController {

    private var track: ReadaloudTrack? = null

    override val state: StateFlow<ReadaloudPlaybackState> = controller.state.map { s ->
        ReadaloudPlaybackState(
            isPlaying = s.isPlaying,
            speed = s.speed,
            currentAudioSrc = s.currentAudioSrc,
            positionSec = s.positionSec,
            positionGlobalSec = s.positionGlobalSec,
            currentChapterIndex = s.currentChapterIndex,
            chapterCount = s.chapterCount,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ReadaloudPlaybackState())

    override val activeFragmentRef: StateFlow<String?> = controller.state.map { s ->
        val t = track ?: return@map null
        val src = s.currentAudioSrc ?: return@map null
        t.activeClipAt(src, s.positionSec)?.textFragmentRef
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val narrationProgress: StateFlow<ReadaloudNarrationProgress?> = controller.state.map { s ->
        val t = track ?: return@map null
        val src = s.currentAudioSrc ?: return@map null
        val clip = t.activeClipAt(src, s.positionSec) ?: return@map null
        val fragmentRef = clip.textFragmentRef.takeIf { it.isNotEmpty() } ?: return@map null
        ReadaloudNarrationProgress(fragmentRef, clip.progressAt(s.positionSec))
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun pause() = controller.pause()
    override fun play() = controller.play()
    override fun setSpeed(speed: Float) = controller.setSpeed(speed)
    override fun skipBy(deltaSec: Double) = controller.skipBy(deltaSec)
    override fun previousChapter() = controller.previousChapter()
    override fun nextChapter() = controller.nextChapter()

    override suspend fun open(bookId: String, bundlePath: String, track: ReadaloudTrack) {
        this.track = track
        controller.prepare(bundlePath, track)
    }

    override suspend fun openStreaming(streamingSession: ReadaloudStreamingSession, track: ReadaloudTrack) {
        val session = streamingSession as? IosReadaloudStreamingSession ?: return
        this.track = track
        controller.prepareStreaming(track, session.items)
    }

    override fun playFromHere(fragmentRef: String) = controller.playFromFragment(fragmentRef)

    override fun playFromReaderPosition(href: String, fragmentId: String?) {
        if (fragmentId != null) controller.playFromFragment(fragmentId)
    }

    override fun playFromSecond(globalSec: Double) = controller.playFromSecond(globalSec)

    override fun close() {
        track = null
        controller.stop()
    }

    override fun releaseForHandoff() = controller.releaseForHandoff()
}
