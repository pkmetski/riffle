package com.riffle.feature.reader

import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral seam between [ReadaloudSession] and the audio engine.
 *
 * Android wires [com.riffle.app.feature.reader.readaloud.PlayerCoordinator] here; iOS wires
 * [com.riffle.shared.readaloud.IosReadaloudPlayerController]. Both hide Media3 / AVFoundation
 * details so the session stays in commonMain.
 */
interface ReadaloudPlayerController {
    val state: StateFlow<ReadaloudPlaybackState>

    /** The SMIL text-fragment ref currently being narrated, or null between clips. */
    val activeFragmentRef: StateFlow<String?>

    /** Within-sentence progress (fraction 0..1) for the intra-sentence column-follow. Null when unsupported. */
    val narrationProgress: StateFlow<ReadaloudNarrationProgress?>

    fun pause()
    fun play()
    fun setSpeed(speed: Float)
    fun skipBy(deltaSec: Double)
    fun previousChapter()
    fun nextChapter()

    /** Connects to [bundlePath] and queues [track]'s audio. [bookId] is required by Media3 on Android. */
    suspend fun open(bookId: String, bundlePath: String, track: ReadaloudTrack)

    /** Streaming counterpart of [open]: audio streams from ABS via a pre-built session. */
    suspend fun openStreaming(streamingSession: ReadaloudStreamingSession, track: ReadaloudTrack)

    /** Seeks to [fragmentRef] and starts playing. */
    fun playFromHere(fragmentRef: String)

    /** Starts at the first full sentence visible at the given reader page. */
    fun playFromReaderPosition(href: String, fragmentId: String?)

    /** Seeks to [globalSec] and starts playing — used for audiobook→readaloud handoff. */
    fun playFromSecond(globalSec: Double)

    /** Stops playback and tears the session down. */
    fun close()

    /** Releases the shared player to the audiobook engine without stopping it. */
    fun releaseForHandoff()
}

/** Within-sentence narration progress. [fraction] is 0..1 across the sentence's audio clip. */
data class ReadaloudNarrationProgress(val fragmentRef: String, val fraction: Double)
