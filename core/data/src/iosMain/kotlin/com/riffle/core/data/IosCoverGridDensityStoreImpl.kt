package com.riffle.core.data

import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.CoverGridScaleEntity
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSUserDefaults

// Global scale (non-library browse screens) has no DB row on either platform — it stays on
// NSUserDefaults, mirroring Android's DataStore-backed global scale. Per-library scale is now
// backed by the real CoverGridScaleDao (core/database), matching CoverGridDensityStoreImpl on
// Android; the NSUserDefaults-per-library workaround this class used before issue #1057 is gone.
internal class IosCoverGridDensityStoreImpl(private val dao: CoverGridScaleDao) : CoverGridDensityStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    private val globalScaleFlow = MutableStateFlow(
        if (defaults.objectForKey(GLOBAL_KEY) != null) defaults.floatForKey(GLOBAL_KEY) else 1f,
    )

    override val scale: Flow<Float> = globalScaleFlow

    override suspend fun setScale(value: Float) {
        defaults.setFloat(value, forKey = GLOBAL_KEY)
        globalScaleFlow.value = value
    }

    override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float> =
        dao.observeScale(sourceId, libraryId, bucket.encode()).map { it ?: 1f }

    override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {
        dao.upsert(CoverGridScaleEntity(sourceId, libraryId, bucket.encode(), value))
    }

    private companion object {
        const val GLOBAL_KEY = "cover_grid_scale"
    }
}
