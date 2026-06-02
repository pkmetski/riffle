package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceIdStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = DeviceIdStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("device_id.preferences_pb") },
        )
    )

    @Test
    fun `getOrCreate returns a non-blank value when DataStore is empty`() = testScope.runTest {
        val id = buildStore().getOrCreate()
        assertTrue("expected a non-blank deviceId, got '$id'", id.isNotBlank())
    }

    @Test
    fun `getOrCreate returns the same value on repeated calls`() = testScope.runTest {
        val store = buildStore()
        val first = store.getOrCreate()
        val second = store.getOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun `deviceId persists across store instances on the same file`() {
        val file = tmp.newFile("device_id_round_trip.preferences_pb")

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val minted = runBlocking {
            DeviceIdStoreImpl(
                PreferenceDataStoreFactory.create(scope = writeScope, produceFile = { file })
            ).getOrCreate()
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val reread = runBlocking {
            DeviceIdStoreImpl(
                PreferenceDataStoreFactory.create(scope = readScope, produceFile = { file })
            ).getOrCreate()
        }
        readScope.cancel()

        assertEquals(minted, reread)
    }
}
