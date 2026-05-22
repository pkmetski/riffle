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

class WakeLockPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = WakeLockPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("wake_lock_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default keepScreenOn is true when DataStore is empty`() = testScope.runTest {
        assertEquals(true, buildStore().keepScreenOn.first())
    }

    @Test
    fun `setKeepScreenOn false returns false on read`() = testScope.runTest {
        val store = buildStore()
        store.setKeepScreenOn(false)
        assertEquals(false, store.keepScreenOn.first())
    }

    @Test
    fun `setKeepScreenOn true after false returns true`() = testScope.runTest {
        val store = buildStore()
        store.setKeepScreenOn(false)
        store.setKeepScreenOn(true)
        assertEquals(true, store.keepScreenOn.first())
    }

    @Test
    fun `keepScreenOn false persists across store instances`() {
        val file = tmp.newFile("wake_lock_round_trip.preferences_pb")

        // Write in a scope that is cancelled before reading — DataStore disallows concurrent
        // instances on the same file, so the write scope must be fully cancelled first.
        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            WakeLockPreferencesStoreImpl(
                PreferenceDataStoreFactory.create(
                    scope = writeScope,
                    produceFile = { file },
                )
            ).setKeepScreenOn(false)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = WakeLockPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(
                scope = readScope,
                produceFile = { file },
            )
        )
        assertEquals(false, runBlocking { store2.keepScreenOn.first() })
        readScope.cancel()
    }
}
