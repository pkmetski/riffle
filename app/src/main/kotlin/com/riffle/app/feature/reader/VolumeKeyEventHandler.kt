package com.riffle.app.feature.reader

object VolumeKeyEventHandler {
    fun handle(
        isVolumeDown: Boolean,
        isReaderActive: Boolean,
        volumeNavEnabled: Boolean,
        invertVolumeKeys: Boolean,
        isPanelOpen: Boolean,
        isAudioPlaying: Boolean,
    ): VolumeKeyAction {
        if (!isReaderActive) return VolumeKeyAction.PassThrough
        // While in-app audio is playing, the volume keys belong to system volume,
        // overriding both page navigation and the panel swallow.
        if (isAudioPlaying) return VolumeKeyAction.PassThrough
        if (!volumeNavEnabled) return VolumeKeyAction.PassThrough
        if (isPanelOpen) return VolumeKeyAction.Swallow
        val goForward = if (invertVolumeKeys) !isVolumeDown else isVolumeDown
        return if (goForward) VolumeKeyAction.NavigateForward else VolumeKeyAction.NavigateBackward
    }
}
