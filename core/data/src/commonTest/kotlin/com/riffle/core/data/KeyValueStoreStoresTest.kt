package com.riffle.core.data

import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyValueStoreStoresTest {

    @Test
    fun volumeKeyPreferencesStoreDefaultsToEnabled() = runTest {
        val store = VolumeKeyPreferencesStoreImpl(FakeKeyValueStore())
        assertTrue(store.volumeKeyNavigationEnabled.first())
        assertFalse(store.invertVolumeKeys.first())
    }

    @Test
    fun volumeKeyPreferencesStoreRoundTrip() = runTest {
        val store = VolumeKeyPreferencesStoreImpl(FakeKeyValueStore())
        store.setVolumeKeyNavigationEnabled(false)
        store.setInvertVolumeKeys(true)
        assertFalse(store.volumeKeyNavigationEnabled.first())
        assertTrue(store.invertVolumeKeys.first())
    }

    @Test
    fun deviceLabelStoreTruncatesLongLabels() = runTest {
        val store = DeviceLabelStoreImpl(FakeKeyValueStore())
        val longLabel = "A".repeat(60)
        store.set(longLabel)
        assertEquals("A".repeat(40), store.get())
    }

    @Test
    fun deviceLabelStoreRoundTripShortLabel() = runTest {
        val store = DeviceLabelStoreImpl(FakeKeyValueStore())
        store.set("My Phone")
        assertEquals("My Phone", store.get())
    }

    @Test
    fun formattingPreferencesStoreRoundTrip() = runTest {
        val store = FormattingPreferencesStoreImpl(FakeKeyValueStore())
        val updated = FormattingPreferences(
            fontSize = 1.5f,
            showChapterMap = true,
            justifyText = false,
        )
        store.update(updated)
        val loaded = store.preferences.first()
        assertEquals(1.5f, loaded.fontSize)
        assertTrue(loaded.showChapterMap)
        assertFalse(loaded.justifyText)
    }

    @Test
    fun formattingPreferencesStoreDefaultsMatchDomainDefaults() = runTest {
        val store = FormattingPreferencesStoreImpl(FakeKeyValueStore())
        val prefs = store.preferences.first()
        assertEquals(FormattingPreferences.DEFAULT_FONT_SIZE, prefs.fontSize)
        assertEquals(FormattingPreferences.DEFAULT_THEME, prefs.theme)
    }

    @Test
    fun setCadencePlatformSupportedDoesNotOverrideUserPreferences() = runTest {
        val store = FormattingPreferencesStoreImpl(FakeKeyValueStore())
        val updated = FormattingPreferences(fontSize = 2.0f)
        store.update(updated)
        store.setCadencePlatformSupported(true)
        val prefs = store.preferences.first()
        assertEquals(2.0f, prefs.fontSize)
        assertTrue(prefs.cadencePlatformSupported)
    }
}
