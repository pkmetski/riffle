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
        is AutoScrollState.Paused -> state.copy(cause = event.cause)
    }
    AutoScrollEvent.ReachedEndOfBook -> when (state) {
        is AutoScrollState.Running -> AutoScrollState.Idle
        else -> state
    }
}
