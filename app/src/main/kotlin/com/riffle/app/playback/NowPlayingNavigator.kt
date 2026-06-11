package com.riffle.app.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot channel for "open whatever is playing now" requests, emitted by [com.riffle.app.MainActivity]
 * when the media-notification intent arrives and collected by the Compose navigation host
 * ([com.riffle.app.navigation.MainScreen]), which reads [NowPlayingStore] to pick the destination.
 * Mirrors `VolumeNavigationController` — a singleton bridge from the activity to Compose nav.
 */
@Singleton
class NowPlayingNavigator @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun requestOpen() {
        _events.tryEmit(Unit)
    }
}
