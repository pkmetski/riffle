package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
class CoverGridDensityStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = CoverGridDensityStore(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("cover_grid_density.preferences_pb") },
        )
    )

    @Test
    fun `default scale is 1 when DataStore is empty`() = testScope.runTest {
        assertEquals(1f, buildStore().scale.first())
    }

    @Test
    fun `setScale round-trips on read`() = testScope.runTest {
        val store = buildStore()
        store.setScale(1.4f)
        assertEquals(1.4f, store.scale.first())
    }

    @Test
    fun `scale persists across store instances`() {
        val file = tmp.newFile("cover_grid_density_round_trip.preferences_pb")

        // DataStore disallows concurrent instances on the same file, so the write scope
        // must be fully cancelled before a second instance reads.
        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            CoverGridDensityStore(
                PreferenceDataStoreFactory.create(
                    scope = writeScope,
                    produceFile = { file },
                )
            ).setScale(0.8f)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = CoverGridDensityStore(
            PreferenceDataStoreFactory.create(
                scope = readScope,
                produceFile = { file },
            )
        )
        assertEquals(0.8f, runBlocking { store2.scale.first() })
        readScope.cancel()
    }
}
