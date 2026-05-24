package com.riffle.app.feature.reader

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VolumeNavigationController @Inject constructor() {
    private val _events = MutableSharedFlow<VolumeNavEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<VolumeNavEvent> = _events.asSharedFlow()

    fun emit(event: VolumeNavEvent) {
        _events.tryEmit(event)
    }
}
