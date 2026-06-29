package com.riffle.app.feature.reader.readaloud

import kotlinx.coroutines.flow.StateFlow

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
    fun setSpeed(speed: Float)
    fun skipBy(deltaSec: Double)
    fun previousChapter()
    fun nextChapter()
}
