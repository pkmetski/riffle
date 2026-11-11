package com.riffle.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAppUpdatePreferencesStoreImplTest {

    private val keys = listOf(
        "app_update:auto_update_enabled",
        "app_update:beta_updates_enabled",
        "app_update:seen_update_version_code",
    )

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @Test
    fun `defaults to auto-update on and zero seen version`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()

        assertTrue(store.autoUpdateEnabled.first())
        assertEquals(0, store.seenUpdateVersionCode.first())
    }

    @Test
    fun `setAutoUpdateEnabled is observable without a restart`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()

        store.setAutoUpdateEnabled(true)

        assertTrue(store.autoUpdateEnabled.first(), "the flow must replay the new value")
    }

    @Test
    fun `setSeenUpdateVersionCode is observable without a restart`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()

        store.setSeenUpdateVersionCode(42)

        assertEquals(42, store.seenUpdateVersionCode.first())
    }

    @Test
    fun `values persist across store instances`() = runTest {
        IosAppUpdatePreferencesStoreImpl().apply {
            setAutoUpdateEnabled(true)
            setSeenUpdateVersionCode(7)
        }

        val reopened = IosAppUpdatePreferencesStoreImpl()

        assertTrue(reopened.autoUpdateEnabled.first(), "auto-update must survive a new instance")
        assertEquals(7, reopened.seenUpdateVersionCode.first())
    }

    @Test
    fun `betaUpdatesEnabled persists across store instances`() = runTest {
        IosAppUpdatePreferencesStoreImpl().setBetaUpdatesEnabled(true)

        val reopened = IosAppUpdatePreferencesStoreImpl()

        assertTrue(reopened.betaUpdatesEnabled.first(), "betaUpdatesEnabled must survive a new instance")
    }

    @Test
    fun `seenUpdateVersionCode persists across store instances`() = runTest {
        IosAppUpdatePreferencesStoreImpl().setSeenUpdateVersionCode(99)

        val reopened = IosAppUpdatePreferencesStoreImpl()

        assertEquals(99, reopened.seenUpdateVersionCode.first(), "seenUpdateVersionCode must survive a new instance")
    }

    @Test
    fun `betaUpdatesEnabled defaults to false`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()
        assertFalse(store.betaUpdatesEnabled.first(), "betaUpdatesEnabled should default to false")
    }

    @Test
    fun `setBetaUpdatesEnabled is observable without a restart`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()
        store.setBetaUpdatesEnabled(true)
        assertTrue(store.betaUpdatesEnabled.first(), "the flow must replay the new value")
    }

    @Test
    fun `seenUpdateVersionCode defaults to zero`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()
        assertEquals(0, store.seenUpdateVersionCode.first(), "seenUpdateVersionCode should default to 0")
    }
}
