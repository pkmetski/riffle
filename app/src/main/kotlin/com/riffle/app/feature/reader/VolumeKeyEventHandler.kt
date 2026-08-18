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
        // null = portrait/ambiguous orientation — fall back to invertVolumeKeys preference.
        volumeUpPointsRight: Boolean? = null,
    ): VolumeKeyAction {
        if (!isReaderActive) return VolumeKeyAction.PassThrough
        // While in-app audio is playing, the volume keys belong to system volume,
        // overriding both page navigation and the panel swallow.
        if (isAudioPlaying) return VolumeKeyAction.PassThrough
        // Auto-Scroll takes precedence over panel-swallow and volumeNavEnabled — the
        // running session repurposes the keys to nudge speed (ADR 0044).
        if (isAutoScrolling) {
            val faster = if (invertVolumeKeys) isVolumeDown else !isVolumeDown
            return if (faster) VolumeKeyAction.AutoScrollFaster else VolumeKeyAction.AutoScrollSlower
        }
        if (!volumeNavEnabled) return VolumeKeyAction.PassThrough
        if (isPanelOpen) return VolumeKeyAction.Swallow
        // In landscape, the physical direction of volume-up overrides the user preference;
        // in portrait (null) the preference is used as-is.
        val goForward = when (volumeUpPointsRight) {
            true  -> !isVolumeDown
            false -> isVolumeDown
            null  -> if (invertVolumeKeys) !isVolumeDown else isVolumeDown
        }
        return if (goForward) VolumeKeyAction.NavigateForward else VolumeKeyAction.NavigateBackward
    }
}
