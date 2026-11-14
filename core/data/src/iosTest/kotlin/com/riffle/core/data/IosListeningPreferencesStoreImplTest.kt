package com.riffle.core.data

import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosListeningPreferencesStoreImplTest {

    private val keys = listOf(
        "default_playback_speed",
        "skip_interval_seconds",
        "rewind_interval_seconds",
        "rewind_on_resume_seconds",
    )

    @BeforeTest
    fun clear() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = keys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    private fun buildStore() = ListeningPreferencesStoreImpl(IosKeyValueStore())

    @Test
    fun defaultPlaybackSpeedIs1x() = runTest {
        assertEquals(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED, buildStore().defaultPlaybackSpeed.first())
    }

    @Test
    fun defaultSkipIntervalIs30Seconds() = runTest {
        assertEquals(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS, buildStore().skipIntervalSeconds.first())
    }

    @Test
    fun defaultRewindIntervalIs15Seconds() = runTest {
        assertEquals(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS, buildStore().rewindIntervalSeconds.first())
    }

    @Test
    fun defaultRewindOnResumeIs0Seconds() = runTest {
        assertEquals(ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS, buildStore().rewindOnResumeSeconds.first())
    }

    @Test
    fun setDefaultPlaybackSpeedRoundtrips() = runTest {
        val store = buildStore()
        store.setDefaultPlaybackSpeed(1.5f)
        assertEquals(1.5f, store.defaultPlaybackSpeed.first())
    }

    @Test
    fun setSkipIntervalSecondsRoundtrips() = runTest {
        val store = buildStore()
        store.setSkipIntervalSeconds(60)
        assertEquals(60, store.skipIntervalSeconds.first())
    }

    @Test
    fun setRewindIntervalSecondsRoundtrips() = runTest {
        val store = buildStore()
        store.setRewindIntervalSeconds(10)
        assertEquals(10, store.rewindIntervalSeconds.first())
    }

    @Test
    fun setRewindOnResumeSecondsRoundtrips() = runTest {
        val store = buildStore()
        store.setRewindOnResumeSeconds(5)
        assertEquals(5, store.rewindOnResumeSeconds.first())
    }

    @Test
    fun valuesPersistAcrossStoreInstances() = runTest {
        buildStore().apply {
            setDefaultPlaybackSpeed(2.0f)
            setSkipIntervalSeconds(45)
            setRewindIntervalSeconds(20)
            setRewindOnResumeSeconds(3)
        }

        val reopened = buildStore()
        assertEquals(2.0f, reopened.defaultPlaybackSpeed.first(), "playback speed must survive a new instance")
        assertEquals(45, reopened.skipIntervalSeconds.first(), "skip interval must survive a new instance")
        assertEquals(20, reopened.rewindIntervalSeconds.first(), "rewind interval must survive a new instance")
        assertEquals(3, reopened.rewindOnResumeSeconds.first(), "rewind on resume must survive a new instance")
    }
}
