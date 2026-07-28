package com.riffle.core.domain

/**
 * Pure last-update-wins reconciliation for the Storyteller single-peer position cycle (ADR 0023).
 * One remote, one canonical local position; whichever has the newer timestamp wins, with no
 * conflict prompt. The orchestrator handles the offline case (a failed GET) before calling this.
 */
object StorytellerPositionReconciler {

    sealed interface Decision {
        /** Remote is newer — jump the reader to it and adopt its timestamp. */
        data class PullRemote(val locatorJson: String, val timestampMillis: Long) : Decision
        /** Local is newer — PATCH it to the server. */
        data class PushLocal(val locatorJson: String, val timestampMillis: Long) : Decision
        data object InSync : Decision
    }

    fun reconcile(
        localLocatorJson: String,
        localUpdatedAt: Long,
        remoteLocatorJson: String?,
        remoteUpdatedAt: Long,
    ): Decision = when {
        remoteLocatorJson != null && remoteUpdatedAt > localUpdatedAt ->
            Decision.PullRemote(remoteLocatorJson, remoteUpdatedAt)
        localUpdatedAt > remoteUpdatedAt && localUpdatedAt > 0 ->
            Decision.PushLocal(localLocatorJson, localUpdatedAt)
        else -> Decision.InSync
    }
}
