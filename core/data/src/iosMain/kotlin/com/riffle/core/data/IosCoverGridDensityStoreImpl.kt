package com.riffle.core.data

import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed CoverGridDensityStore for iOS.
// Per-library scales are keyed by "cover_scale:<sourceId>:<libraryId>:<bucket>" since there
// is no Room DAO on iOS. The global scale uses the same key as the Android DataStore store
// for cross-platform debugging symmetry.
internal class IosCoverGridDensityStoreImpl : CoverGridDensityStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()
    private val perLibraryFlows = mutableMapOf<String, MutableStateFlow<Float>>()

    private val globalScaleFlow = MutableStateFlow(
        if (defaults.objectForKey(GLOBAL_KEY) != null) defaults.floatForKey(GLOBAL_KEY) else 1f,
    )

    override val scale: Flow<Float> = globalScaleFlow

    override suspend fun setScale(value: Float) {
        defaults.setFloat(value, forKey = GLOBAL_KEY)
        globalScaleFlow.value = value
    }

    override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float> =
        stateFor(sourceId, libraryId, bucket)

    override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {
        val key = perLibraryKey(sourceId, libraryId, bucket)
        defaults.setFloat(value, forKey = key)
        stateFor(sourceId, libraryId, bucket).value = value
    }

    private fun stateFor(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): MutableStateFlow<Float> {
        val key = perLibraryKey(sourceId, libraryId, bucket)
        lock.lock()
        try {
            return perLibraryFlows.getOrPut(key) {
                val stored = if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else 1f
                MutableStateFlow(stored)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun perLibraryKey(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket) =
        "cover_scale:$sourceId:$libraryId:${bucket.encode()}"

    private companion object {
        const val GLOBAL_KEY = "cover_grid_scale"
    }
}
