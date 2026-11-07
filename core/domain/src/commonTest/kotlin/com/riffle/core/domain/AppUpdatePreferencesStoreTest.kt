package com.riffle.core.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
    fun ignoredVersionWasPreReleaseDefaultsFalse() = runTest {
        assertFalse(fake.ignoredVersionWasPreRelease.first(), "ignoredVersionWasPreRelease should default to false")
    }

    @Test
    fun setIgnoredVersionWasPreReleasePersists() = runTest {
        fake.setIgnoredVersionWasPreRelease(true)
        assertTrue(fake.ignoredVersionWasPreRelease.first(), "ignoredVersionWasPreRelease should be true after setting true")
    }
}
