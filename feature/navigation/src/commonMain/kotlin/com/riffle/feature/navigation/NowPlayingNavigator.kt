package com.riffle.feature.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot channel for "open whatever is playing now" requests, emitted by the platform host
 * (`MainActivity` on Android) when the media-notification intent arrives and collected by the
 * navigation host (`MainScreen`), which reads `NowPlayingStore` to pick the destination.
 * Mirrors `VolumeNavigationController` — a singleton bridge from the platform host to nav.
 */

class NowPlayingNavigator constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun requestOpen() {
        _events.tryEmit(Unit)
    }
}
