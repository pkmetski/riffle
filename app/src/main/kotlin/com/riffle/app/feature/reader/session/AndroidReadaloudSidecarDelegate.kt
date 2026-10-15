package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.feature.reader.ReadaloudSidecarDelegate
import com.riffle.feature.reader.ReadaloudSidecarState
import com.riffle.feature.reader.ReadaloudStreamingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Adapts the Android [ReadaloudSidecarStore] and [ReadaloudStreamingSessionFactory] to the
 * platform-neutral [ReadaloudSidecarDelegate] consumed by the common [com.riffle.feature.reader.ReadaloudSession].
 */
class AndroidReadaloudSidecarDelegate(
    private val sidecarStore: ReadaloudSidecarStore,
    private val streamingSessionFactory: ReadaloudStreamingSessionFactory,
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
        sidecarStore.cachedFile(sourceId, itemId)?.absolutePath

    override fun prepare(sourceId: String, itemId: String) = sidecarStore.prepare(sourceId, itemId)

    override suspend fun tryBuildStreamingSession(sourceId: String, itemId: String): ReadaloudStreamingSession? =
        streamingSessionFactory.tryBuild(sourceId, itemId)?.let { AndroidReadaloudStreamingSession(it) }

    override fun remove(sourceId: String, itemId: String) = sidecarStore.remove(sourceId, itemId)
}
