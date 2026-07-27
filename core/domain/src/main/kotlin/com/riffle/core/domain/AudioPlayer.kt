package com.riffle.core.domain

/**
 * Platform-agnostic audio playback contract.
 *
 * ExoPlayer (via Media3) implements this at the Android layer in [app]; a JVM test double
 * or a future non-Android target can implement it independently. This phase only creates
 * the boundary — the ExoPlayer wiring is not yet moved out of the Android module.
 */
interface AudioPlayer {

    val isPlaying: Boolean

    /** Current playback position in milliseconds (book-absolute for audiobooks). */
    val currentPositionMs: Long

    /** Total duration in milliseconds; -1 if unknown. */
    val durationMs: Long

    val playbackState: PlaybackState

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setPlaybackSpeed(speed: Float)

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlaybackStateChanged(state: PlaybackState) {}
    }

    enum class PlaybackState {
        /** Player is idle; no media loaded. */
        IDLE,
        /** Player is loading or buffering. */
        BUFFERING,
        /** Player is ready to play. */
        READY,
        /** Playback has finished. */
        ENDED,
    }
}
