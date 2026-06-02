package com.riffle.core.data

import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.StorytellerPositionReconciler
import com.riffle.core.domain.StorytellerPositionReconciler.Decision
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerPositionResult
import com.riffle.core.network.StorytellerPositionApi

sealed interface StorytellerSyncOutcome {
    /** Remote was newer — the reader should jump to [locatorJson]. */
    data class PulledRemote(val locatorJson: String) : StorytellerSyncOutcome
    data object PushedLocal : StorytellerSyncOutcome
    data object InSync : StorytellerSyncOutcome
    data object Offline : StorytellerSyncOutcome
}

/**
 * Storyteller-only single-peer position sync (ADR 0023). Runs on the reader's existing ~30 s
 * cadence: GET the remote position, reconcile last-update-wins against the local canonical
 * position (no conflict prompt), then PATCH or jump as the [StorytellerPositionReconciler] decides.
 * The local canonical position is stored as the Readium locator JSON in [ReadingPositionStore].
 */
class StorytellerPositionSyncController(
    private val api: StorytellerPositionApi,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) {

    suspend fun runCycle(itemId: String, localLocatorJson: String): StorytellerSyncOutcome {
        val server = serverRepository.getActive() ?: return StorytellerSyncOutcome.Offline
        val token = tokenStorage.getToken(server.id) ?: return StorytellerSyncOutcome.Offline
        val localUpdatedAt = positionStore.loadLocalUpdatedAt(server.id, itemId)

        val remote = when (val r = api.getPosition(server.url.value, itemId, token, server.insecureConnectionAllowed)) {
            is NetworkStorytellerPositionResult.Success -> r.locatorJson to r.timestampMillis
            is NetworkStorytellerPositionResult.NoPosition -> null to 0L
            is NetworkStorytellerPositionResult.NetworkError -> return StorytellerSyncOutcome.Offline
        }

        return when (val d = StorytellerPositionReconciler.reconcile(localLocatorJson, localUpdatedAt, remote.first, remote.second)) {
            is Decision.PullRemote -> {
                positionStore.save(server.id, itemId, d.locatorJson)
                positionStore.updateLocalTimestamp(server.id, itemId, d.timestampMillis)
                StorytellerSyncOutcome.PulledRemote(d.locatorJson)
            }
            is Decision.PushLocal -> {
                api.putPosition(server.url.value, itemId, d.locatorJson, d.timestampMillis, token, server.insecureConnectionAllowed)
                StorytellerSyncOutcome.PushedLocal
            }
            Decision.InSync -> StorytellerSyncOutcome.InSync
        }
    }
}
