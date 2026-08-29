package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.CoverGridScaleEntity
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    private val fakeDao = FakeCoverGridScaleDao()

    private fun buildStore() = CoverGridDensityStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("cover_grid_density.preferences_pb") },
        ),
        fakeDao,
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
        val fakeDao = FakeCoverGridScaleDao()

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            CoverGridDensityStoreImpl(
                PreferenceDataStoreFactory.create(scope = writeScope, produceFile = { file }),
                fakeDao,
            ).setScale(0.8f)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = CoverGridDensityStoreImpl(
            PreferenceDataStoreFactory.create(scope = readScope, produceFile = { file }),
            fakeDao,
        )
        assertEquals(0.8f, runBlocking { store2.scale.first() })
        readScope.cancel()
    }

    @Test
    fun `per-library scale defaults to 1 when no row exists`() = testScope.runTest {
        val bucket = ScreenDimensionBucket.PhonePortrait
        assertEquals(1f, buildStore().scale("src1", "lib1", bucket).first())
    }

    @Test
    fun `per-library setScale round-trips on read`() = testScope.runTest {
        val store = buildStore()
        val bucket = ScreenDimensionBucket.PhonePortrait
        store.setScale("src1", "lib1", bucket, 1.3f)
        assertEquals(1.3f, store.scale("src1", "lib1", bucket).first())
    }

    @Test
    fun `per-library scales are keyed independently`() = testScope.runTest {
        val store = buildStore()
        val bucket = ScreenDimensionBucket.PhonePortrait
        store.setScale("src1", "lib1", bucket, 1.2f)
        store.setScale("src1", "lib2", bucket, 0.9f)
        assertEquals(1.2f, store.scale("src1", "lib1", bucket).first())
        assertEquals(0.9f, store.scale("src1", "lib2", bucket).first())
    }

    @Test
    fun `per-library scale does not affect global scale`() = testScope.runTest {
        val store = buildStore()
        val bucket = ScreenDimensionBucket.PhonePortrait
        store.setScale("src1", "lib1", bucket, 1.5f)
        assertEquals(1f, store.scale.first())
    }
}

private class FakeCoverGridScaleDao : CoverGridScaleDao {
    private val rows = MutableStateFlow<Map<Triple<String, String, String>, Float>>(emptyMap())

    override suspend fun upsert(entity: CoverGridScaleEntity) {
        rows.value = rows.value + (Triple(entity.sourceId, entity.libraryId, entity.screenDimensionBucket) to entity.scale)
    }

    override fun observeScale(sourceId: String, libraryId: String, bucket: String): Flow<Float?> =
        rows.map { it[Triple(sourceId, libraryId, bucket)] }
}
