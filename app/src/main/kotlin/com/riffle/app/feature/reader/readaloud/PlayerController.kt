package com.riffle.app.feature.reader.readaloud

import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Thin testable seam over [PlayerCoordinator]. [ReadaloudSession] depends on this
 * interface so unit tests can supply a [FakePlayerController] without an Android
 * MediaController. Production wiring is provided by [PlayerCoordinator].
 *
 * Keep this surface minimal — add methods only when [ReadaloudSession] actually
 * calls them.
 */
interface PlayerController {
    val state: StateFlow<ReadaloudController.PlaybackState>
    val activeFragmentRef: StateFlow<String?>
    val narrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?>
    fun pause()
    fun play()
    fun setSpeed(speed: Float)
    fun skipBy(deltaSec: Double)
    fun previousChapter()
    fun nextChapter()
    /** Connects the controller to [bundleFile] and queues [track]'s audio. */
    suspend fun open(itemId: String, bundleFile: File, track: ReadaloudTrack)
    /** Streaming counterpart of [open] (ADR 0028): audio streams from ABS. */
    suspend fun openStreaming(streaming: SharedBundle.Streaming, track: ReadaloudTrack)
    /** "Play from here" from text-selection or resume. */
    fun playFromHere(fragmentRef: String)
    /** Starts playback at the reader's current position. */
    fun playFromReaderPosition(href: String, fragmentId: String?)
    /** Seeks to [globalSec] and starts playing — the audiobook→readaloud handoff. */
    fun playFromSecond(globalSec: Double)
    /** Stops playback and tears the session down. */
    fun close()
    /** Releases the shared player to the audiobook player without stopping it. */
    fun releaseForHandoff()
}
