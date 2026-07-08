package com.riffle.core.catalog

/**
 * Opaque handle returned from [ReadingSessionsCapability.openSession]. Callers pass it back into
 * [ReadingSessionsCapability.syncSession] / [ReadingSessionsCapability.closeSession].
 */
data class CatalogSessionHandle(
    val sessionId: String,
    val itemId: String,
    val startedAtEpochMs: Long,
)
