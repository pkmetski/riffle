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

/**
 * Regression coverage for the stub this replaced (issue #1065): the previous
 * IosNoOpAppUpdatePreferencesStore emitted a constant `false`/`0` and dropped every write, so the
 * Settings auto-update toggle and "skip this version" choice silently did nothing.
 */
class IosAppUpdatePreferencesStoreImplTest {

    private val keys = listOf("app_update:auto_update_enabled", "app_update:ignored_version_code")

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @Test
    fun `defaults to auto-update off and no ignored version`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()

        assertFalse(store.autoUpdateEnabled.first())
        assertEquals(0, store.ignoredVersionCode.first())
    }

    @Test
    fun `setAutoUpdateEnabled is observable without a restart`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()

        store.setAutoUpdateEnabled(true)

        assertTrue(store.autoUpdateEnabled.first(), "the flow must replay the new value")
    }

    @Test
    fun `setIgnoredVersionCode is observable without a restart`() = runTest {
        val store = IosAppUpdatePreferencesStoreImpl()

        store.setIgnoredVersionCode(42)

        assertEquals(42, store.ignoredVersionCode.first())
    }

    @Test
    fun `values persist across store instances`() = runTest {
        IosAppUpdatePreferencesStoreImpl().apply {
            setAutoUpdateEnabled(true)
            setIgnoredVersionCode(7)
        }

        val reopened = IosAppUpdatePreferencesStoreImpl()

        assertTrue(reopened.autoUpdateEnabled.first(), "auto-update must survive a new instance")
        assertEquals(7, reopened.ignoredVersionCode.first())
    }
}
