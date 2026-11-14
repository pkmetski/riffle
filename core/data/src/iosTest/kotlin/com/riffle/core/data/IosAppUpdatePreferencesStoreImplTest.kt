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

    private val keys = listOf("auto_update_enabled", "beta_updates_enabled", "seen_update_version_code")

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    private fun buildStore() = AppUpdatePreferencesStoreImpl(IosKeyValueStore())

    @Test
    fun `defaults to auto-update on and zero seen version`() = runTest {
        val store = buildStore()

        assertTrue(store.autoUpdateEnabled.first())
        assertEquals(0, store.seenUpdateVersionCode.first())
    }

    @Test
    fun `setAutoUpdateEnabled is observable without a restart`() = runTest {
        val store = buildStore()

        store.setAutoUpdateEnabled(true)

        assertTrue(store.autoUpdateEnabled.first(), "the flow must replay the new value")
    }

    @Test
    fun `setSeenUpdateVersionCode is observable without a restart`() = runTest {
        val store = buildStore()

        store.setSeenUpdateVersionCode(42)

        assertEquals(42, store.seenUpdateVersionCode.first())
    }

    @Test
    fun `values persist across store instances`() = runTest {
        buildStore().apply {
            setAutoUpdateEnabled(true)
            setSeenUpdateVersionCode(7)
        }

        val reopened = buildStore()

        assertTrue(reopened.autoUpdateEnabled.first(), "auto-update must survive a new instance")
        assertEquals(7, reopened.seenUpdateVersionCode.first())
    }

    @Test
    fun `betaUpdatesEnabled persists across store instances`() = runTest {
        buildStore().setBetaUpdatesEnabled(true)

        val reopened = buildStore()

        assertTrue(reopened.betaUpdatesEnabled.first(), "betaUpdatesEnabled must survive a new instance")
    }

    @Test
    fun `seenUpdateVersionCode persists across store instances`() = runTest {
        buildStore().setSeenUpdateVersionCode(99)

        val reopened = buildStore()

        assertEquals(99, reopened.seenUpdateVersionCode.first(), "seenUpdateVersionCode must survive a new instance")
    }

    @Test
    fun `betaUpdatesEnabled defaults to false`() = runTest {
        val store = buildStore()
        assertFalse(store.betaUpdatesEnabled.first(), "betaUpdatesEnabled should default to false")
    }

    @Test
    fun `setBetaUpdatesEnabled is observable without a restart`() = runTest {
        val store = buildStore()
        store.setBetaUpdatesEnabled(true)
        assertTrue(store.betaUpdatesEnabled.first(), "the flow must replay the new value")
    }

    @Test
    fun `seenUpdateVersionCode defaults to zero`() = runTest {
        val store = buildStore()
        assertEquals(0, store.seenUpdateVersionCode.first(), "seenUpdateVersionCode should default to 0")
    }
}
