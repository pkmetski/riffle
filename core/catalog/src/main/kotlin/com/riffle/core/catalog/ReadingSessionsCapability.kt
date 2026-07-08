package com.riffle.core.catalog

/**
 * Server-side session lifecycle for a playing/reading item. Distinct from [ProgressPeerCapability]:
 * a session is a bounded playback interval the peer tracks for stats/analytics, while progress is
 * the last-known position independent of any open session.
 */
interface ReadingSessionsCapability : CatalogCapability {
    suspend fun openSession(itemId: String, deviceLabel: String): CatalogSessionHandle

    suspend fun syncSession(
        handle: CatalogSessionHandle,
        currentTimeSec: Double,
        timeListenedSec: Double,
    )

    suspend fun closeSession(
        handle: CatalogSessionHandle,
        currentTimeSec: Double,
        timeListenedSec: Double,
    )
}
