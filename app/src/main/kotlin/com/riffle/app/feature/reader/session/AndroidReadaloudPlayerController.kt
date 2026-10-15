package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.feature.reader.ReadaloudNarrationProgress
import com.riffle.feature.reader.ReadaloudPlaybackState
import com.riffle.feature.reader.ReadaloudPlayerController
import com.riffle.feature.reader.ReadaloudStreamingSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import java.io.File

/**
 * Adapts Android's [PlayerController] (which uses [java.io.File] and [com.riffle.app.feature.reader.readaloud.SharedBundle.Streaming])
 * to the common [ReadaloudPlayerController] interface (which uses path strings and [ReadaloudStreamingSession]).
 *
 * The common [com.riffle.feature.reader.ReadaloudSession] calls into this adapter; the adapter
 * delegates to the real [PlayerController] implementation ([com.riffle.app.feature.reader.readaloud.PlayerCoordinator]).
 */
class AndroidReadaloudPlayerController(
    private val delegate: PlayerController,
    scope: CoroutineScope,
) : ReadaloudPlayerController {

    override val state: StateFlow<ReadaloudPlaybackState> = delegate.state
        .map { s ->
            ReadaloudPlaybackState(
                isPlaying = s.isPlaying,
                speed = s.speed,
                currentAudioSrc = s.currentAudioSrc,
                positionSec = s.positionSec,
                positionGlobalSec = s.positionGlobalSec,
                currentChapterIndex = s.currentChapterIndex,
                chapterCount = s.chapterCount,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ReadaloudPlaybackState())

    override val activeFragmentRef: StateFlow<String?> get() = delegate.activeFragmentRef

    override val narrationProgress: StateFlow<ReadaloudNarrationProgress?> = delegate.narrationProgress
        .map { p -> p?.let { ReadaloudNarrationProgress(it.fragmentRef, it.fraction) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun pause() = delegate.pause()
    override fun play() = delegate.play()
    override fun setSpeed(speed: Float) = delegate.setSpeed(speed)
    override fun skipBy(deltaSec: Double) = delegate.skipBy(deltaSec)
    override fun previousChapter() = delegate.previousChapter()
    override fun nextChapter() = delegate.nextChapter()

    override suspend fun open(bookId: String, bundlePath: String, track: ReadaloudTrack) {
        delegate.open(bookId, File(bundlePath), track)
    }

    override suspend fun openStreaming(streamingSession: ReadaloudStreamingSession, track: ReadaloudTrack) {
        val session = streamingSession as AndroidReadaloudStreamingSession
        delegate.openStreaming(session.streaming, track)
    }

    override fun playFromHere(fragmentRef: String) = delegate.playFromHere(fragmentRef)
    override fun playFromReaderPosition(href: String, fragmentId: String?) = delegate.playFromReaderPosition(href, fragmentId)
    override fun playFromSecond(globalSec: Double) = delegate.playFromSecond(globalSec)
    override fun close() = delegate.close()
    override fun releaseForHandoff() = delegate.releaseForHandoff()
}
