package com.riffle.core.domain.autoscroll

sealed interface AutoScrollState {
    object Idle : AutoScrollState
    data class Running(val speed: AutoScrollSpeed) : AutoScrollState
    data class Paused(val speed: AutoScrollSpeed, val cause: PauseCause) : AutoScrollState
}

enum class PauseCause {
    AppBackgrounded,
    ScreenOff,
    ManualScroll,
    TextSelection,
    OrientationChange,
    PanelOpen,
    ReadaloudStarted,
}

val AutoScrollState.isActive: Boolean
    get() = this is AutoScrollState.Running

val AutoScrollState.speedOrNull: AutoScrollSpeed?
    get() = when (this) {
        is AutoScrollState.Idle -> null
        is AutoScrollState.Running -> speed
        is AutoScrollState.Paused -> speed
    }
