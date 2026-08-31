package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.CoverGridScaleEntity
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CoverGridDensityStoreImpl constructor(
    dataStore: DataStore<Preferences>,
    private val dao: CoverGridScaleDao,
) : CoverGridDensityStore {

    private val globalStore = preferenceStore(dataStore, PrefCodecs.float("cover_grid_scale", default = 1f))

    override val scale: Flow<Float> = globalStore.flow

    override suspend fun setScale(value: Float) = globalStore.update(value)

    override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float> =
        dao.observeScale(sourceId, libraryId, bucket.encode()).map { it ?: 1f }

    override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {
        dao.upsert(CoverGridScaleEntity(sourceId, libraryId, bucket.encode(), value))
    }
}
