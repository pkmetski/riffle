package com.riffle.app.feature.reader

object VolumeKeyEventHandler {
    fun handle(
        isVolumeDown: Boolean,
        isReaderActive: Boolean,
        volumeNavEnabled: Boolean,
        invertVolumeKeys: Boolean,
        isPanelOpen: Boolean,
    ): VolumeKeyAction {
        if (!isReaderActive) return VolumeKeyAction.PassThrough
        if (!volumeNavEnabled) return VolumeKeyAction.PassThrough
        if (isPanelOpen) return VolumeKeyAction.Swallow
        val goForward = if (invertVolumeKeys) !isVolumeDown else isVolumeDown
        return if (goForward) VolumeKeyAction.NavigateForward else VolumeKeyAction.NavigateBackward
    }
}
