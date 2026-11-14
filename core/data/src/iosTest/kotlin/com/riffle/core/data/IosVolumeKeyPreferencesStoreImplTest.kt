package com.riffle.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosVolumeKeyPreferencesStoreImplTest {

    private val keys = listOf("volume_key_navigation_enabled", "invert_volume_keys")

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    private fun buildStore() = VolumeKeyPreferencesStoreImpl(IosKeyValueStore())

    @Test
    fun defaultVolumeKeyNavigationEnabledIsTrue() = runTest {
        assertTrue(buildStore().volumeKeyNavigationEnabled.first())
    }

    @Test
    fun defaultInvertVolumeKeysIsFalse() = runTest {
        assertFalse(buildStore().invertVolumeKeys.first())
    }

    @Test
    fun setVolumeKeyNavigationEnabledToFalseRoundtrips() = runTest {
        val store = buildStore()
        store.setVolumeKeyNavigationEnabled(false)
        assertFalse(store.volumeKeyNavigationEnabled.first())
    }

    @Test
    fun setInvertVolumeKeysToTrueRoundtrips() = runTest {
        val store = buildStore()
        store.setInvertVolumeKeys(true)
        assertTrue(store.invertVolumeKeys.first())
    }

    @Test
    fun volumeKeyNavigationEnabledPersistsAcrossStoreInstances() = runTest {
        buildStore().setVolumeKeyNavigationEnabled(false)

        val reopened = buildStore()
        assertFalse(reopened.volumeKeyNavigationEnabled.first(), "disabled state must survive a new instance")
    }

    @Test
    fun invertVolumeKeysPersistsAcrossStoreInstances() = runTest {
        buildStore().setInvertVolumeKeys(true)

        val reopened = buildStore()
        assertTrue(reopened.invertVolumeKeys.first(), "inverted state must survive a new instance")
    }
}
