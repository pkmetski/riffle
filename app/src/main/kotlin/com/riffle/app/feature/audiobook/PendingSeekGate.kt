package com.riffle.app.feature.audiobook

/**
 * Suppresses the client-side [androidx.media3.session.MediaController] position mirror during the
 * window between a seek command being issued and the server relaying the position discontinuity
 * that confirms it landed. After [onSeekIssued] the client mirror snaps to the raw local-track
 * `offsetMs` we just sent — projecting that as book-absolute time (the caller does that further up)
 * would paint the seek bar at a bogus early position for one poll tick after every track-crossing
 * seek. [sample] returns the held target until [onDiscontinuity] clears it.
 */
internal class PendingSeekGate {
    var pendingSec: Double? = null
        private set

    fun onSeekIssued(absoluteSec: Double) { pendingSec = absoluteSec }
    fun onDiscontinuity() { pendingSec = null }
    fun reset() { pendingSec = null }

    inline fun sample(fallback: () -> Double): Double = pendingSec ?: fallback()
}
