package com.riffle.core.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatePreferencesStoreTest {

    private val fake = FakeAppUpdatePreferencesStore()

    @Test
    fun betaUpdatesEnabledDefaultsFalse() = runTest {
        assertFalse(fake.betaUpdatesEnabled.first(), "betaUpdatesEnabled should default to false")
    }

    @Test
    fun setBetaUpdatesEnabledPersists() = runTest {
        fake.setBetaUpdatesEnabled(true)
        assertTrue(fake.betaUpdatesEnabled.first(), "betaUpdatesEnabled should be true after setting true")
    }

    @Test
    fun seenUpdateVersionCodeDefaultsZero() = runTest {
        assertEquals(0, fake.seenUpdateVersionCode.first(), "seenUpdateVersionCode should default to 0")
    }

    @Test
    fun setSeenUpdateVersionCodePersists() = runTest {
        fake.setSeenUpdateVersionCode(42)
        assertEquals(42, fake.seenUpdateVersionCode.first(), "seenUpdateVersionCode should be 42 after setting 42")
    }
}
