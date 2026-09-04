package com.riffle.feature.reader

import com.riffle.core.domain.ReadaloudResumePosition

/**
 * Result of an audio-led reconciliation cycle. Used by [AudiobookPlayerViewModel] and
 * [FollowLoopOrchestrator] to apply cross-device position jumps.
 */
data class AudioLedCycleResult(val jumpToAudioSec: Double?, val canonicalLastUpdate: Long)

/**
 * Platform-agnostic view of the reader-side position coordinator for a matched ABS book (ADR 0023).
 * The Android implementation is [com.riffle.feature.reader.ReaderSyncCoordinator] (jvmMain).
 *
 * Only the subset of methods that [AudiobookPlayerViewModel] and [FollowLoopOrchestrator] call
 * is declared here; the concrete class adds additional ebook-reader-facing methods.
 */
interface ReaderSyncCoordinatorInterface {
    suspend fun runAudioLedCycle(currentAudioSec: Double, localUpdatedAt: Long): AudioLedCycleResult
    val ebookItemId: String?
    fun canonicalForAudioSeconds(seconds: Double): String?
    fun readaloudAnchorForAudioSeconds(seconds: Double): ReadaloudResumePosition?
}

/**
 * Platform-agnostic view of the bundle-only audiobook follow path (ADR 0023, 0029).
 * The Android implementation is [com.riffle.feature.reader.AudiobookFollow] (jvmMain).
 */
interface AudiobookFollowInterface {
    val ebookItemId: String?
    fun ebookLocatorForAudioSeconds(seconds: Double): String?
    fun readaloudAnchorForAudioSeconds(seconds: Double): ReadaloudResumePosition?
}

/**
 * Platform-agnostic factory for creating [ReaderSyncCoordinatorInterface] and
 * [AudiobookFollowInterface] for a given audiobook item. The Android implementation is
 * [com.riffle.feature.reader.ReaderSyncFactory] (jvmMain).
 */
interface ReaderSyncFactoryInterface {
    suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinatorInterface?
    suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollowInterface?
}
