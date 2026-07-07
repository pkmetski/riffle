package com.riffle.core.domain.cadence

import com.riffle.core.domain.autoscroll.AutoScrollSpeed

/**
 * Events driving [CadenceState] transitions. Mirrors `AutoScrollEvent` — same lifecycle shape so
 * volume-key nudges, backgrounding, orientation flips, and text-selection interruptions all pause
 * the same way both features do.
 */
sealed interface CadenceEvent {
    object Start : CadenceEvent
    object Stop : CadenceEvent
    data class NudgeSpeed(val by: Int) : CadenceEvent
    data class Pause(val cause: PauseCause) : CadenceEvent
    object Resume : CadenceEvent
    object ReachedEndOfBook : CadenceEvent
}

/**
 * Pure state transition — mirrors `autoscroll.reduce`. The mutual-exclusion pairing with Readaloud
 * and Auto-Scroll is enforced by the caller (Cadence controller / reader ViewModel), NOT here — the
 * reducer is single-feature, and the arbiter fans out `Pause(ReadaloudStarted)` or
 * `Pause(AutoScrollStarted)` at the seam. Keeping the reducer feature-local matches how
 * `AutoScrollReducer` treats `Pause(ReadaloudStarted)`.
 */
fun reduce(
    state: CadenceState,
    event: CadenceEvent,
    defaultSpeed: AutoScrollSpeed,
): CadenceState = when (event) {
    CadenceEvent.Start -> when (state) {
        CadenceState.Idle -> CadenceState.Running(defaultSpeed)
        is CadenceState.Running -> state
        is CadenceState.Paused -> CadenceState.Running(state.speed)
    }
    CadenceEvent.Resume -> when (state) {
        CadenceState.Idle -> state
        is CadenceState.Running -> state
        is CadenceState.Paused -> CadenceState.Running(state.speed)
    }
    CadenceEvent.Stop -> CadenceState.Idle
    is CadenceEvent.NudgeSpeed -> when (state) {
        CadenceState.Idle -> state
        is CadenceState.Running -> CadenceState.Running(state.speed.nudge(event.by))
        is CadenceState.Paused -> state.copy(speed = state.speed.nudge(event.by))
    }
    is CadenceEvent.Pause -> when (state) {
        CadenceState.Idle -> state
        is CadenceState.Running -> CadenceState.Paused(state.speed, event.cause)
        is CadenceState.Paused -> state.copy(cause = event.cause)
    }
    CadenceEvent.ReachedEndOfBook -> when (state) {
        is CadenceState.Running -> CadenceState.Idle
        else -> state
    }
}
