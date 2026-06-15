package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = ListeningPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("listening_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default playback speed is 1_0 when DataStore is empty`() = testScope.runTest {
        assertEquals(1.0f, buildStore().defaultPlaybackSpeed.first())
    }

    @Test
    fun `setDefaultPlaybackSpeed persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setDefaultPlaybackSpeed(1.5f)
        assertEquals(1.5f, store.defaultPlaybackSpeed.first())
    }

    @Test
    fun `default skipIntervalSeconds is 30 when DataStore is empty`() = testScope.runTest {
        assertEquals(30, buildStore().skipIntervalSeconds.first())
    }

    @Test
    fun `setSkipIntervalSeconds persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setSkipIntervalSeconds(15)
        assertEquals(15, store.skipIntervalSeconds.first())
    }

    @Test
    fun `default rewindIntervalSeconds is 15 when DataStore is empty`() = testScope.runTest {
        assertEquals(15, buildStore().rewindIntervalSeconds.first())
    }

    @Test
    fun `setRewindIntervalSeconds persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setRewindIntervalSeconds(10)
        assertEquals(10, store.rewindIntervalSeconds.first())
    }

    @Test
    fun `default rewindOnResumeSeconds is 0 when DataStore is empty`() = testScope.runTest {
        assertEquals(0, buildStore().rewindOnResumeSeconds.first())
    }

    @Test
    fun `setRewindOnResumeSeconds persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setRewindOnResumeSeconds(10)
        assertEquals(10, store.rewindOnResumeSeconds.first())
    }

    @Test
    fun `settings persist across store instances`() {
        val file = tmp.newFile("listening_round_trip.preferences_pb")

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            val store = ListeningPreferencesStoreImpl(
                PreferenceDataStoreFactory.create(scope = writeScope, produceFile = { file })
            )
            store.setDefaultPlaybackSpeed(2.0f)
            store.setSkipIntervalSeconds(45)
            store.setRewindIntervalSeconds(10)
            store.setRewindOnResumeSeconds(5)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = ListeningPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(scope = readScope, produceFile = { file })
        )
        assertEquals(2.0f, runBlocking { store2.defaultPlaybackSpeed.first() })
        assertEquals(45, runBlocking { store2.skipIntervalSeconds.first() })
        assertEquals(10, runBlocking { store2.rewindIntervalSeconds.first() })
        assertEquals(5, runBlocking { store2.rewindOnResumeSeconds.first() })
        readScope.cancel()
    }
}
