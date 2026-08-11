package com.riffle.app.feature.audiobook

/**
 * Suppresses the client-side [androidx.media3.session.MediaController] position mirror during the
 * window between a seek command being issued and the server relaying the confirmed book-absolute
 * position. After [onSeekIssued] the client mirror snaps to the raw local-track `offsetMs` we just
 * sent — projecting that as book-absolute time would paint the seek bar at a bogus early position
 * for multiple poll ticks after every track-crossing seek.
 *
 * [sample] returns the held target until [maybeConfirm] observes that [MediaController.currentPosition]
 * has converged to the expected book-absolute target (within [CONFIRM_EPSILON_SEC]), which means the
 * [AbsolutePositionPlayer] projection on the service side has been applied and relayed back. Clearing
 * on [EVENT_POSITION_DISCONTINUITY] is wrong: Media3 fires that event optimistically from the
 * [seekTo] call itself, before the session responds, with [currentPosition] still holding the raw
 * per-track offsetMs rather than the projected absolute value.
 */
internal class PendingSeekGate {
    var pendingSec: Double? = null
        private set

    fun onSeekIssued(absoluteSec: Double) { pendingSec = absoluteSec }

    /** Clears [pendingSec] only once [rawPositionSec] has converged to the held target. */
    fun maybeConfirm(rawPositionSec: Double) {
        val p = pendingSec ?: return
        if (kotlin.math.abs(rawPositionSec - p) < CONFIRM_EPSILON_SEC) pendingSec = null
    }

    fun onDiscontinuity() { pendingSec = null }
    fun reset() { pendingSec = null }

    inline fun sample(fallback: () -> Double): Double = pendingSec ?: fallback()

    companion object {
        const val CONFIRM_EPSILON_SEC = 0.5
    }
}
