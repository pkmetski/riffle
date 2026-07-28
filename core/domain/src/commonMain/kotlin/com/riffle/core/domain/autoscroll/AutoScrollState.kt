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
    UserPausedPill,
}

// The HUD pill is visible while Auto-Scroll is Running, or while the user has explicitly paused it
// from the pill itself. All other pause causes (backgrounding, panel open, manual scroll, etc.) hide
// the pill immediately — those are transient system-driven pauses, not a user parking action.
val AutoScrollState.isHudPillVisible: Boolean
    get() = this is AutoScrollState.Running ||
        (this is AutoScrollState.Paused && cause == PauseCause.UserPausedPill)

val AutoScrollState.isActive: Boolean
    get() = this is AutoScrollState.Running

val AutoScrollState.speedOrNull: AutoScrollSpeed?
    get() = when (this) {
        is AutoScrollState.Idle -> null
        is AutoScrollState.Running -> speed
        is AutoScrollState.Paused -> speed
    }
