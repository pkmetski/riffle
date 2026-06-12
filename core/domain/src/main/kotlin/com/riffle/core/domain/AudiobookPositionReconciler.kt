package com.riffle.core.domain

/**
 * Pure last-update-wins reconciliation for the audiobook's single durable local position against
 * ABS's media-progress record (ADR 0029). Mirrors [StorytellerPositionReconciler] but over absolute
 * seconds: whichever side has the newer timestamp wins; ties stay in sync (local-favoured). The
 * caller resolves the offline case (a missing local row → [localSec] null, [localUpdatedAt] 0).
 */
object AudiobookPositionReconciler {

    sealed interface Decision {
        /** Remote is newer — resume at it and adopt its timestamp into the local row. */
        data class PullRemote(val positionSec: Double, val timestampMillis: Long) : Decision
        /** Local is newer — resume at it (the follow loop / push converges ABS). */
        data class PushLocal(val positionSec: Double, val timestampMillis: Long) : Decision
        data object InSync : Decision
    }

    fun reconcile(
        localSec: Double?,
        localUpdatedAt: Long,
        remoteSec: Double,
        remoteUpdatedAt: Long,
    ): Decision = when {
        remoteUpdatedAt > localUpdatedAt ->
            Decision.PullRemote(remoteSec, remoteUpdatedAt)
        localSec != null && localUpdatedAt > remoteUpdatedAt && localUpdatedAt > 0 ->
            Decision.PushLocal(localSec, localUpdatedAt)
        else -> Decision.InSync
    }
}
