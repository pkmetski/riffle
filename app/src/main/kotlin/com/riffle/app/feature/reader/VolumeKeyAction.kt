package com.riffle.app.feature.reader

sealed class VolumeKeyAction {
    data object PassThrough : VolumeKeyAction()
    data object Swallow : VolumeKeyAction()
    data object NavigateForward : VolumeKeyAction()
    data object NavigateBackward : VolumeKeyAction()
}
