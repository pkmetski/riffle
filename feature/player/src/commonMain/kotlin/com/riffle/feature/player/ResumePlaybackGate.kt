package com.riffle.feature.player

/**
 * Decides when a latched "play" intent may actually start the audiobook player.
 *
 * A resume-at-position open buffers from the saved point, but starting before the player reaches
 * STATE_READY lets the decoder emit a brief blip from the track's start before the in-track seek
 * settles — the audible "plays from the beginning for a moment" symptom. Buffering happens regardless
 * of whether playback is started, so deferring the start until ready costs nothing but the blip.
 */
object ResumePlaybackGate {
    /** Whether a latched play intent may start now: only once the player has buffered to its position. */
    fun shouldStart(wantsToPlay: Boolean, ready: Boolean): Boolean = wantsToPlay && ready
}
