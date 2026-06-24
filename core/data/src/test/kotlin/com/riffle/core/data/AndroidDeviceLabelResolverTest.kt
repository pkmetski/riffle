package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceLabelResolverTest {

    @Test
    fun `composeBuildLabel drops manufacturer when MODEL already starts with it (case-insensitive)`() {
        assertEquals(
            "Samsung Galaxy S24",
            AndroidDeviceLabelResolver.composeBuildLabel("Samsung", "Samsung Galaxy S24"),
        )
        assertEquals(
            "samsung galaxy s24",
            AndroidDeviceLabelResolver.composeBuildLabel("Samsung", "samsung galaxy s24"),
        )
    }

    @Test
    fun `composeBuildLabel prepends manufacturer when MODEL does not start with it`() {
        assertEquals(
            "Google Pixel 9 Pro",
            AndroidDeviceLabelResolver.composeBuildLabel("Google", "Pixel 9 Pro"),
        )
        assertEquals(
            "Samsung SM-G991B",
            AndroidDeviceLabelResolver.composeBuildLabel("Samsung", "SM-G991B"),
        )
    }

    @Test
    fun `composeBuildLabel handles missing manufacturer`() {
        assertEquals("SM-G991B", AndroidDeviceLabelResolver.composeBuildLabel("", "SM-G991B"))
        assertEquals("SM-G991B", AndroidDeviceLabelResolver.composeBuildLabel(null, "SM-G991B"))
    }

    @Test
    fun `composeBuildLabel handles missing model`() {
        assertEquals("Google", AndroidDeviceLabelResolver.composeBuildLabel("Google", ""))
        assertEquals("Google", AndroidDeviceLabelResolver.composeBuildLabel("Google", null))
    }

    @Test
    fun `looksLikeEmulator detects Google emulator MODEL`() {
        assertTrue(
            AndroidDeviceLabelResolver.looksLikeEmulator(
                manufacturer = "Google",
                model = "Android SDK built for arm64",
                fingerprint = "google/sdk_gphone64_arm64/...",
                product = "sdk_gphone64_arm64",
            ),
        )
    }

    @Test
    fun `looksLikeEmulator detects AOSP generic build`() {
        assertTrue(
            AndroidDeviceLabelResolver.looksLikeEmulator(
                manufacturer = "Google",
                model = "AOSP on x86",
                fingerprint = "generic/aosp_x86/aosp_x86:7.1.1/...",
                product = "aosp_x86",
            ),
        )
    }

    @Test
    fun `looksLikeEmulator does not false-positive a real Pixel`() {
        assertFalse(
            AndroidDeviceLabelResolver.looksLikeEmulator(
                manufacturer = "Google",
                model = "Pixel 9 Pro",
                fingerprint = "google/komodo/komodo:14/...",
                product = "komodo",
            ),
        )
    }

    @Test
    fun `isEmulatorLookingLabel catches DEVICE_NAME emulator presets`() {
        assertTrue(AndroidDeviceLabelResolver.isEmulatorLookingLabel("Android SDK built for arm64"))
        assertTrue(AndroidDeviceLabelResolver.isEmulatorLookingLabel("sdk_gphone64_arm64"))
        assertTrue(AndroidDeviceLabelResolver.isEmulatorLookingLabel("Android Emulator"))
        assertFalse(AndroidDeviceLabelResolver.isEmulatorLookingLabel("Plamen's Pixel"))
        assertFalse(AndroidDeviceLabelResolver.isEmulatorLookingLabel("Samsung Galaxy S24"))
    }
}
