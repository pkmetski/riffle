package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKeyEventHandlerTest {

    // Helper so tests read naturally: handle(volumeDown, readerActive, navEnabled, invert, panelOpen, audioPlaying)
    private fun handle(
        isVolumeDown: Boolean,
        isReaderActive: Boolean = true,
        volumeNavEnabled: Boolean = true,
        invertVolumeKeys: Boolean = false,
        isPanelOpen: Boolean = false,
        isAudioPlaying: Boolean = false,
        isAutoScrolling: Boolean = false,
    ) = VolumeKeyEventHandler.handle(
        isVolumeDown = isVolumeDown,
        isReaderActive = isReaderActive,
        volumeNavEnabled = volumeNavEnabled,
        invertVolumeKeys = invertVolumeKeys,
        isPanelOpen = isPanelOpen,
        isAudioPlaying = isAudioPlaying,
        isAutoScrolling = isAutoScrolling,
    )

    @Test
    fun `returns PassThrough when reader is not the active screen`() {
        assertEquals(VolumeKeyAction.PassThrough, handle(isVolumeDown = true, isReaderActive = false))
        assertEquals(VolumeKeyAction.PassThrough, handle(isVolumeDown = false, isReaderActive = false))
    }

    @Test
    fun `returns PassThrough when volume key navigation is disabled`() {
        assertEquals(VolumeKeyAction.PassThrough, handle(isVolumeDown = true, volumeNavEnabled = false))
        assertEquals(VolumeKeyAction.PassThrough, handle(isVolumeDown = false, volumeNavEnabled = false))
    }

    @Test
    fun `returns Swallow when a panel is open`() {
        assertEquals(VolumeKeyAction.Swallow, handle(isVolumeDown = true, isPanelOpen = true))
        assertEquals(VolumeKeyAction.Swallow, handle(isVolumeDown = false, isPanelOpen = true))
    }

    @Test
    fun `returns PassThrough while audio is playing, even when nav is enabled`() {
        assertEquals(VolumeKeyAction.PassThrough, handle(isVolumeDown = true, isAudioPlaying = true))
        assertEquals(VolumeKeyAction.PassThrough, handle(isVolumeDown = false, isAudioPlaying = true))
    }

    @Test
    fun `audio playback wins over an open panel`() {
        assertEquals(
            VolumeKeyAction.PassThrough,
            handle(isVolumeDown = true, isPanelOpen = true, isAudioPlaying = true),
        )
    }

    @Test
    fun `audio playback wins regardless of invert setting`() {
        assertEquals(
            VolumeKeyAction.PassThrough,
            handle(isVolumeDown = true, invertVolumeKeys = true, isAudioPlaying = true),
        )
    }

    @Test
    fun `volume-down navigates forward when not inverted`() {
        assertEquals(VolumeKeyAction.NavigateForward, handle(isVolumeDown = true, invertVolumeKeys = false))
    }

    @Test
    fun `volume-up navigates backward when not inverted`() {
        assertEquals(VolumeKeyAction.NavigateBackward, handle(isVolumeDown = false, invertVolumeKeys = false))
    }

    @Test
    fun `volume-down navigates backward when inverted`() {
        assertEquals(VolumeKeyAction.NavigateBackward, handle(isVolumeDown = true, invertVolumeKeys = true))
    }

    @Test
    fun `volume-up navigates forward when inverted`() {
        assertEquals(VolumeKeyAction.NavigateForward, handle(isVolumeDown = false, invertVolumeKeys = true))
    }

    @Test
    fun `auto-scroll volume-up speeds up when not inverted`() {
        assertEquals(
            VolumeKeyAction.AutoScrollFaster,
            handle(isVolumeDown = false, isAutoScrolling = true),
        )
    }

    @Test
    fun `auto-scroll volume-down slows down when not inverted`() {
        assertEquals(
            VolumeKeyAction.AutoScrollSlower,
            handle(isVolumeDown = true, isAutoScrolling = true),
        )
    }

    @Test
    fun `auto-scroll inverts speed direction with invertVolumeKeys`() {
        assertEquals(
            VolumeKeyAction.AutoScrollSlower,
            handle(isVolumeDown = false, isAutoScrolling = true, invertVolumeKeys = true),
        )
        assertEquals(
            VolumeKeyAction.AutoScrollFaster,
            handle(isVolumeDown = true, isAutoScrolling = true, invertVolumeKeys = true),
        )
    }

    @Test
    fun `auto-scroll overrides panel swallow and volumeNavEnabled`() {
        assertEquals(
            VolumeKeyAction.AutoScrollFaster,
            handle(
                isVolumeDown = false,
                isAutoScrolling = true,
                isPanelOpen = true,
                volumeNavEnabled = false,
            ),
        )
    }

    @Test
    fun `audio playback still wins over auto-scroll`() {
        assertEquals(
            VolumeKeyAction.PassThrough,
            handle(isVolumeDown = true, isAutoScrolling = true, isAudioPlaying = true),
        )
    }

}
