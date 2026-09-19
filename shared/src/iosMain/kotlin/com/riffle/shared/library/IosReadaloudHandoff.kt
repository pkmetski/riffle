package com.riffle.shared.library

import com.riffle.core.domain.ReadaloudTrack
import com.riffle.feature.player.ReadaloudHandoff

/**
 * iOS [ReadaloudHandoff] (ADR 0039). During the drag gesture that hands playback between the
 * readaloud and the audiobook player, the incoming side pre-resolves the seek target so the commit
 * does not have to run the SMIL lookup while the user's finger is still down.
 *
 * Android's implementation lives inside `ReadaloudController`, which owns the loaded track; iOS
 * has no narration controller yet, so the track is handed in via [setTrack] when a readaloud
 * session loads (and cleared with null when it ends). The resolution itself is the shared
 * [ReadaloudTrack.seekTarget], so both platforms pre-warm to the identical position.
 *
 * With no track loaded — an audiobook-only entry with no readaloud session this app lifetime —
 * pre-warming is a no-op, exactly as on Android.
 */
internal class IosReadaloudHandoff : ReadaloudHandoff {

    private var track: ReadaloudTrack? = null
    private var preWarmedPosition: ReadaloudTrack.Position? = null

    /** Called when a readaloud session loads its track, or with null when the session ends. */
    fun setTrack(track: ReadaloudTrack?) {
        this.track = track
        if (track == null) preWarmedPosition = null
    }

    override fun preWarmSeek(globalSec: Double) {
        preWarmedPosition = track?.seekTarget(globalSec)
    }

    override fun cancelPreWarm() {
        preWarmedPosition = null
    }

    /**
     * The pre-resolved target, consumed by the commit step. Reading it clears the pre-warm so a
     * stale target from an earlier drag can never be applied to a later one.
     */
    fun consumePreWarmedPosition(): ReadaloudTrack.Position? =
        preWarmedPosition.also { preWarmedPosition = null }
}
