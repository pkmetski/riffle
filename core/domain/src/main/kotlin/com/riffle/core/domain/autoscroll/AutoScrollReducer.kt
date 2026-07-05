package com.riffle.core.domain.autoscroll

sealed interface AutoScrollEvent {
    object Start : AutoScrollEvent
    object Stop : AutoScrollEvent
    data class NudgeSpeed(val by: Int) : AutoScrollEvent
    data class Pause(val cause: PauseCause) : AutoScrollEvent
    object Resume : AutoScrollEvent
    object ReachedEndOfBook : AutoScrollEvent
}

fun reduce(
    state: AutoScrollState,
    event: AutoScrollEvent,
    defaultSpeed: AutoScrollSpeed,
): AutoScrollState = when (event) {
    AutoScrollEvent.Start -> when (state) {
        AutoScrollState.Idle -> AutoScrollState.Running(defaultSpeed)
        is AutoScrollState.Running -> state
        is AutoScrollState.Paused -> AutoScrollState.Running(state.speed)
    }
    AutoScrollEvent.Resume -> when (state) {
        AutoScrollState.Idle -> state
        is AutoScrollState.Running -> state
        is AutoScrollState.Paused -> AutoScrollState.Running(state.speed)
    }
    AutoScrollEvent.Stop -> AutoScrollState.Idle
    is AutoScrollEvent.NudgeSpeed -> when (state) {
        AutoScrollState.Idle -> state
        is AutoScrollState.Running -> AutoScrollState.Running(state.speed.nudge(event.by))
        is AutoScrollState.Paused -> state.copy(speed = state.speed.nudge(event.by))
    }
    is AutoScrollEvent.Pause -> when (state) {
        AutoScrollState.Idle -> state
        is AutoScrollState.Running -> AutoScrollState.Paused(state.speed, event.cause)
        // A user's explicit park via the HUD pill is sticky: transient system-driven pauses
        // (panel open, manual scroll, etc.) must not overwrite it, or resuming those pauses
        // would silently un-park the user's explicit stop.
        is AutoScrollState.Paused ->
            if (state.cause == PauseCause.UserPausedPill) state
            else state.copy(cause = event.cause)
    }
    AutoScrollEvent.ReachedEndOfBook -> when (state) {
        is AutoScrollState.Running -> AutoScrollState.Idle
        else -> state
    }
}
