package com.riffle.core.data

import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StorytellerPositionReconciler
import com.riffle.core.domain.StorytellerPositionReconciler.Decision
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkResult
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
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) {

    suspend fun runCycle(itemId: String, localLocatorJson: String): StorytellerSyncOutcome {
        val source = sourceRepository.getActive() ?: return StorytellerSyncOutcome.Offline
        val token = tokenStorage.getToken(source.id) ?: return StorytellerSyncOutcome.Offline
        val localUpdatedAt = positionStore.loadLocalUpdatedAt(source.id, itemId)

        val getRes = api.getPosition(source.url.value, itemId, token, source.insecureConnectionAllowed)
        if (getRes !is NetworkResult.Success) return StorytellerSyncOutcome.Offline
        val remote: Pair<String?, Long> = getRes.value?.let { it.locatorJson to it.timestampMillis } ?: (null to 0L)

        return when (val d = StorytellerPositionReconciler.reconcile(localLocatorJson, localUpdatedAt, remote.first, remote.second)) {
            is Decision.PullRemote -> {
                positionStore.save(source.id, itemId, d.locatorJson)
                positionStore.updateLocalTimestamp(source.id, itemId, d.timestampMillis)
                StorytellerSyncOutcome.PulledRemote(d.locatorJson)
            }
            is Decision.PushLocal -> {
                api.putPosition(source.url.value, itemId, d.locatorJson, d.timestampMillis, token, source.insecureConnectionAllowed)
                StorytellerSyncOutcome.PushedLocal
            }
            Decision.InSync -> StorytellerSyncOutcome.InSync
        }
    }
}
