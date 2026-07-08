package com.riffle.core.catalog

/**
 * A peer that stores per-item reading/listening progress and can be synced against. Ebook and
 * audiobook progress use separate push endpoints because backends model them separately (ADR 0029);
 * pull returns a unified [CatalogProgress] envelope for reconciliation.
 */
interface ProgressPeerCapability : CatalogCapability {
    suspend fun pushEbookProgress(
        itemId: String,
        location: String,
        progress: Float,
        isFinished: Boolean,
        lastUpdateEpochMs: Long,
    )

    suspend fun pushAudiobookProgress(
        itemId: String,
        currentTimeSec: Double,
        durationSec: Double,
        isFinished: Boolean,
        lastUpdateEpochMs: Long,
    )

    suspend fun pullProgress(itemId: String): CatalogProgress?

    /** Bulk pull for reconciliation cycles. Empty when no items have progress on this peer. */
    suspend fun pullAllProgress(): List<CatalogProgress>
}
