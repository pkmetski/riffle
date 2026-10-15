package com.riffle.feature.reader

/**
 * Platform-neutral abstraction over the Storyteller position-sync controller.
 *
 * Android implements this with `StorytellerPositionSyncController`.
 * iOS returns [ReadaloudServerSyncOutcome.Offline] (no Storyteller-only position sync on iOS).
 */
interface StorytellerSyncDelegate {
    suspend fun runCycle(itemId: String, locatorJson: String): ReadaloudServerSyncOutcome
}

sealed interface ReadaloudServerSyncOutcome {
    /** Remote position was newer — the reader should navigate to [locatorJson]. */
    data class PulledRemote(val locatorJson: String) : ReadaloudServerSyncOutcome
    data object PushedLocal : ReadaloudServerSyncOutcome
    data object InSync : ReadaloudServerSyncOutcome
    data object Offline : ReadaloudServerSyncOutcome
}
