package com.riffle.core.domain

import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.flow.Flow

interface CoverGridDensityStore {
    // Global scale — used by non-library browse screens (Chitanka, Gutenberg, web-source).
    val scale: Flow<Float>
    suspend fun setScale(value: Float)

    // Per-library scale keyed by source + library + screen size class.
    // `bucket` is ScreenDimensionBucket.encode() — e.g. "Compact_Medium" — so a phone in
    // portrait and in landscape share one row (rotation-invariant, per ADR 0029).
    fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float>
    suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float)
}
