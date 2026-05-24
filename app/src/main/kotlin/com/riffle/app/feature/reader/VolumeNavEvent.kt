package com.riffle.app.feature.reader

sealed class VolumeNavEvent {
    data object Forward : VolumeNavEvent()
    data object Backward : VolumeNavEvent()
}
