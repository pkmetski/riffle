package com.riffle.core.data

import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for IosListeningPreferencesStoreImpl: verifies default values, field
 * round-trips, and cross-instance persistence.
 */
class IosListeningPreferencesStoreImplTest {

    private val keys = listOf(
        "listening.default_playback_speed",
        "listening.skip_interval_seconds",
        "listening.rewind_interval_seconds",
        "listening.rewind_on_resume_seconds",
    )

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @Test
    fun defaultPlaybackSpeedIs1x() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        assertEquals(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED, store.defaultPlaybackSpeed.first())
    }

    @Test
    fun defaultSkipIntervalIs30Seconds() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        assertEquals(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS, store.skipIntervalSeconds.first())
    }

    @Test
    fun defaultRewindIntervalIs15Seconds() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        assertEquals(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS, store.rewindIntervalSeconds.first())
    }

    @Test
    fun defaultRewindOnResumeIs0Seconds() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        assertEquals(ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS, store.rewindOnResumeSeconds.first())
    }

    @Test
    fun setDefaultPlaybackSpeedRoundtrips() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        store.setDefaultPlaybackSpeed(1.5f)
        assertEquals(1.5f, store.defaultPlaybackSpeed.first())
    }

    @Test
    fun setSkipIntervalSecondsRoundtrips() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        store.setSkipIntervalSeconds(60)
        assertEquals(60, store.skipIntervalSeconds.first())
    }

    @Test
    fun setRewindIntervalSecondsRoundtrips() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        store.setRewindIntervalSeconds(10)
        assertEquals(10, store.rewindIntervalSeconds.first())
    }

    @Test
    fun setRewindOnResumeSecondsRoundtrips() = runTest {
        val store = IosListeningPreferencesStoreImpl()
        store.setRewindOnResumeSeconds(5)
        assertEquals(5, store.rewindOnResumeSeconds.first())
    }

    @Test
    fun valuesPersistAcrossStoreInstances() = runTest {
        IosListeningPreferencesStoreImpl().apply {
            setDefaultPlaybackSpeed(2.0f)
            setSkipIntervalSeconds(45)
            setRewindIntervalSeconds(20)
            setRewindOnResumeSeconds(3)
        }

        val reopened = IosListeningPreferencesStoreImpl()
        assertEquals(2.0f, reopened.defaultPlaybackSpeed.first(), "playback speed must survive a new instance")
        assertEquals(45, reopened.skipIntervalSeconds.first(), "skip interval must survive a new instance")
        assertEquals(20, reopened.rewindIntervalSeconds.first(), "rewind interval must survive a new instance")
        assertEquals(3, reopened.rewindOnResumeSeconds.first(), "rewind on resume must survive a new instance")
    }
}
