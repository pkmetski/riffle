package com.riffle.shared.readaloud

import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.feature.reader.ReadaloudSidecarDelegate
import com.riffle.feature.reader.ReadaloudSidecarState
import com.riffle.feature.reader.ReadaloudStreamingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * iOS counterpart to `AndroidReadaloudSidecarDelegate` (ADR 0040): adapts the shared
 * [ReadaloudSidecarStore] and [IosReadaloudStreamingSessionFactory] to the platform-neutral
 * [ReadaloudSidecarDelegate] interface consumed by the shared [com.riffle.feature.reader.ReadaloudSession].
 */
internal class IosReadaloudSidecarDelegate(
    private val sidecarStore: ReadaloudSidecarStore,
    private val streamingSessionFactory: IosReadaloudStreamingSessionFactory,
) : ReadaloudSidecarDelegate {

    override val states: Flow<Map<String, ReadaloudSidecarState>> = sidecarStore.states.map { byKey ->
        byKey.mapValues { (_, state) ->
            when (state) {
                ReadaloudSidecarStore.State.Preparing -> ReadaloudSidecarState.Preparing
                ReadaloudSidecarStore.State.Ready -> ReadaloudSidecarState.Ready
                ReadaloudSidecarStore.State.Failed -> ReadaloudSidecarState.Failed
            }
        }
    }

    override fun key(sourceId: String, itemId: String): String = sidecarStore.key(sourceId, itemId)

    override fun cachedBundlePath(sourceId: String, itemId: String): String? =
        sidecarStore.cachedPath(sourceId, itemId)

    override fun prepare(sourceId: String, itemId: String) = sidecarStore.prepare(sourceId, itemId)

    override suspend fun tryBuildStreamingSession(sourceId: String, itemId: String): ReadaloudStreamingSession? =
        streamingSessionFactory.tryBuild(sourceId, itemId)

    override fun remove(sourceId: String, itemId: String) = sidecarStore.remove(sourceId, itemId)
}
