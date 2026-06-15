package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.ReadingSpeedTracker
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
class ReadingSpeedStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = ReadingSpeedStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("reading_speed.preferences_pb") },
        )
    )

    @Test
    fun `default speed equals ReadingSpeedTracker DEFAULT_SECS_PER_POSITION`() = testScope.runTest {
        assertEquals(
            ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION,
            buildStore().speedSecPerPosition.first(),
            0.001,
        )
    }

    @Test
    fun `updateSpeed stores and re-reads the value`() = testScope.runTest {
        val store = buildStore()
        store.updateSpeed(50.0)
        assertEquals(50.0, store.speedSecPerPosition.first(), 0.001)
    }

    @Test
    fun `updated value persists across store instances`() {
        val file = tmp.newFile("reading_speed_round_trip.preferences_pb")
        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            ReadingSpeedStoreImpl(
                PreferenceDataStoreFactory.create(
                    scope = writeScope,
                    produceFile = { file },
                )
            ).updateSpeed(45.0)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = ReadingSpeedStoreImpl(
            PreferenceDataStoreFactory.create(
                scope = readScope,
                produceFile = { file },
            )
        )
        assertEquals(45.0, runBlocking { store2.speedSecPerPosition.first() }, 0.001)
        readScope.cancel()
    }
}
