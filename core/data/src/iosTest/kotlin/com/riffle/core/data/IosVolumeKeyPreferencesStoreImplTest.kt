package com.riffle.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for IosVolumeKeyPreferencesStoreImpl: verifies default values, round-trips,
 * and cross-instance persistence.
 *
 * Note: volume-key interception is not exposed in the iOS UI (the OS owns hardware keys), but the
 * preference is stored so both platforms report the same values for a shared account.
 */
class IosVolumeKeyPreferencesStoreImplTest {

    private val keys = listOf("volume_key_navigation_enabled", "invert_volume_keys")

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @Test
    fun defaultVolumeKeyNavigationEnabledIsTrue() = runTest {
        val store = IosVolumeKeyPreferencesStoreImpl()
        assertTrue(store.volumeKeyNavigationEnabled.first())
    }

    @Test
    fun defaultInvertVolumeKeysIsFalse() = runTest {
        val store = IosVolumeKeyPreferencesStoreImpl()
        assertFalse(store.invertVolumeKeys.first())
    }

    @Test
    fun setVolumeKeyNavigationEnabledToFalseRoundtrips() = runTest {
        val store = IosVolumeKeyPreferencesStoreImpl()
        store.setVolumeKeyNavigationEnabled(false)
        assertFalse(store.volumeKeyNavigationEnabled.first())
    }

    @Test
    fun setInvertVolumeKeysToTrueRoundtrips() = runTest {
        val store = IosVolumeKeyPreferencesStoreImpl()
        store.setInvertVolumeKeys(true)
        assertTrue(store.invertVolumeKeys.first())
    }

    @Test
    fun volumeKeyNavigationEnabledPersistsAcrossStoreInstances() = runTest {
        IosVolumeKeyPreferencesStoreImpl().setVolumeKeyNavigationEnabled(false)

        val reopened = IosVolumeKeyPreferencesStoreImpl()
        assertFalse(reopened.volumeKeyNavigationEnabled.first(), "disabled state must survive a new instance")
    }

    @Test
    fun invertVolumeKeysPersistsAcrossStoreInstances() = runTest {
        IosVolumeKeyPreferencesStoreImpl().setInvertVolumeKeys(true)

        val reopened = IosVolumeKeyPreferencesStoreImpl()
        assertTrue(reopened.invertVolumeKeys.first(), "inverted state must survive a new instance")
    }
}
