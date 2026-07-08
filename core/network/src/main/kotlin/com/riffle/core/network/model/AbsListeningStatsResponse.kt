package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/me/listening-stats` payload. ABS returns a much larger structure (per-day, per-item,
 * recent sessions); we only care about aggregate seconds — the Catalog's [StatsCapability] surfaces
 * a compact summary.
 */
@Serializable
internal data class AbsListeningStatsResponse(
    val totalTime: Double = 0.0,
)
