package com.riffle.app.feature.reader

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Holds the single "page to return to" after an internal-link jump moved the reader off it, and the
 * one-shot navigation request the screen acts on when the reader taps "Back".
 *
 * Generic over the position type [T] (the reader uses Readium's `Locator`) so it carries no Android /
 * Readium navigator deps — the return-affordance behaviour (capture, replace, return, dismiss) is then
 * unit-testable in isolation from [EpubReaderViewModel], which owns the bound instance and surfaces its
 * flows.
 *
 * Single-level by design: a later off-page jump replaces the target; a return or a dismiss clears it.
 * It does NOT clear on page turns — the caller reads around the target and can still go back.
 */
class ReturnNavigator<T : Any> {
    private val _target = MutableStateFlow<T?>(null)

    /** Non-null while a "Back" affordance should be shown; its value is where Back goes. */
    val target: StateFlow<T?> = _target

    private val _navChannel = Channel<T>(Channel.CONFLATED)

    /** Emits once per [returnToOrigin]; the screen navigates the reader to the emitted position. */
    val navEvents: Flow<T> = _navChannel.receiveAsFlow()

    /** Remember [origin] as the page to return to. Replaces any previously captured origin. */
    fun capture(origin: T) {
        _target.value = origin
    }

    /** "Back" tapped — request navigation to the captured origin and clear the affordance. No-op if none. */
    fun returnToOrigin() {
        val origin = _target.value ?: return
        _target.value = null
        _navChannel.trySend(origin)
    }

    /** "✕" tapped — drop the affordance and the captured origin without navigating. */
    fun dismiss() {
        _target.value = null
    }
}
