package com.riffle.shared.readaloud

import com.riffle.feature.reader.ReadaloudSidecarDelegate
import com.riffle.feature.reader.ReadaloudSidecarState
import com.riffle.feature.reader.ReadaloudStreamingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS no-op sidecar delegate. iOS bundles are always fully downloaded; sidecar streaming
 * is not needed.
 */
internal object IosReadaloudSidecarDelegate : ReadaloudSidecarDelegate {
    override val states: Flow<Map<String, ReadaloudSidecarState>> = flowOf(emptyMap())
    override fun key(sourceId: String, itemId: String): String = "$sourceId/$itemId"
    override fun cachedBundlePath(sourceId: String, itemId: String): String? = null
    override fun prepare(sourceId: String, itemId: String) {}
    override suspend fun tryBuildStreamingSession(sourceId: String, itemId: String): ReadaloudStreamingSession? = null
    override fun remove(sourceId: String, itemId: String) {}
}
