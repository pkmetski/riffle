package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
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

class VolumeKeyPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = VolumeKeyPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("volume_key_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default volumeKeyNavigationEnabled is true when DataStore is empty`() = testScope.runTest {
        assertEquals(true, buildStore().volumeKeyNavigationEnabled.first())
    }

    @Test
    fun `setVolumeKeyNavigationEnabled false returns false on read`() = testScope.runTest {
        val store = buildStore()
        store.setVolumeKeyNavigationEnabled(false)
        assertEquals(false, store.volumeKeyNavigationEnabled.first())
    }

    @Test
    fun `setVolumeKeyNavigationEnabled true after false returns true`() = testScope.runTest {
        val store = buildStore()
        store.setVolumeKeyNavigationEnabled(false)
        store.setVolumeKeyNavigationEnabled(true)
        assertEquals(true, store.volumeKeyNavigationEnabled.first())
    }

    @Test
    fun `default invertVolumeKeys is false when DataStore is empty`() = testScope.runTest {
        assertEquals(false, buildStore().invertVolumeKeys.first())
    }

    @Test
    fun `setInvertVolumeKeys true returns true on read`() = testScope.runTest {
        val store = buildStore()
        store.setInvertVolumeKeys(true)
        assertEquals(true, store.invertVolumeKeys.first())
    }

    @Test
    fun `setInvertVolumeKeys false after true returns false`() = testScope.runTest {
        val store = buildStore()
        store.setInvertVolumeKeys(true)
        store.setInvertVolumeKeys(false)
        assertEquals(false, store.invertVolumeKeys.first())
    }

    @Test
    fun `both preferences persist across store instances`() {
        val file = tmp.newFile("volume_key_round_trip.preferences_pb")

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            val store = VolumeKeyPreferencesStoreImpl(
                PreferenceDataStoreFactory.create(
                    scope = writeScope,
                    produceFile = { file },
                )
            )
            store.setVolumeKeyNavigationEnabled(false)
            store.setInvertVolumeKeys(true)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = VolumeKeyPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(
                scope = readScope,
                produceFile = { file },
            )
        )
        assertEquals(false, runBlocking { store2.volumeKeyNavigationEnabled.first() })
        assertEquals(true, runBlocking { store2.invertVolumeKeys.first() })
        readScope.cancel()
    }
}
