package com.riffle.app.feature.reader

object VolumeKeyEventHandler {
    fun handle(
        isVolumeDown: Boolean,
        isReaderActive: Boolean,
        volumeNavEnabled: Boolean,
        invertVolumeKeys: Boolean,
        isPanelOpen: Boolean,
        isAudioPlaying: Boolean,
        isAutoScrolling: Boolean = false,
    ): VolumeKeyAction {
        if (!isReaderActive) return VolumeKeyAction.PassThrough
        // While in-app audio is playing, the volume keys belong to system volume,
        // overriding both page navigation and the panel swallow.
        if (isAudioPlaying) return VolumeKeyAction.PassThrough
        // Auto-Scroll takes precedence over panel-swallow and volumeNavEnabled — the
        // running session repurposes the keys to nudge speed (ADR 0037).
        if (isAutoScrolling) {
            val faster = if (invertVolumeKeys) isVolumeDown else !isVolumeDown
            return if (faster) VolumeKeyAction.AutoScrollFaster else VolumeKeyAction.AutoScrollSlower
        }
        if (!volumeNavEnabled) return VolumeKeyAction.PassThrough
        if (isPanelOpen) return VolumeKeyAction.Swallow
        val goForward = if (invertVolumeKeys) !isVolumeDown else isVolumeDown
        return if (goForward) VolumeKeyAction.NavigateForward else VolumeKeyAction.NavigateBackward
    }
}
