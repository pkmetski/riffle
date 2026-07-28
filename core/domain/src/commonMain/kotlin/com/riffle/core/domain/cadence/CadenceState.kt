package com.riffle.core.domain.cadence

import com.riffle.core.domain.autoscroll.AutoScrollSpeed

/**
 * Cadence session state — parallel to `AutoScrollState`. Cadence, Auto-Scroll, and Readaloud are
 * mutually exclusive: at most one may be [Running] at a time. The arbiter in `CadenceArbiter` /
 * `AutoScrollArbiter` enforces this at the reducer level.
 */
sealed interface CadenceState {
    object Idle : CadenceState
    data class Running(val speed: AutoScrollSpeed) : CadenceState
    data class Paused(val speed: AutoScrollSpeed, val cause: PauseCause) : CadenceState
}

enum class PauseCause {
    AppBackgrounded,
    ScreenOff,
    ManualScroll,
    TextSelection,
    OrientationChange,
    PanelOpen,
    ReadaloudStarted,
    AutoScrollStarted,
}

val CadenceState.isActive: Boolean
    get() = this is CadenceState.Running

val CadenceState.speedOrNull: AutoScrollSpeed?
    get() = when (this) {
        is CadenceState.Idle -> null
        is CadenceState.Running -> speed
        is CadenceState.Paused -> speed
    }
