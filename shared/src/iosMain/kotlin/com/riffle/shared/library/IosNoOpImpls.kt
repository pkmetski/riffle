package com.riffle.shared.library

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object IosNoOpStorytellerSyncer : StorytellerReadaloudCacheSyncer {
    override suspend fun syncStale() {}
}

internal object IosNoOpReadaloudReconciler : ReadaloudLinkReconciler {
    override suspend fun reconcileLinks() {}
}

internal object IosNoOpApplicationScope : ApplicationScope {
    private val supervisor = SupervisorJob()
    override val coroutineScope: CoroutineScope = CoroutineScope(supervisor)
    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        coroutineScope.launch(block = block)
    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        block(coroutineScope)
    override fun scopeOn(dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(supervisor + dispatcher)
}

// IosNoOpAudiobookBookmarkStore / IosNoOpReadaloudLinkRepository removed (issue #1065): iOS now
// binds the real AudiobookBookmarkStoreImpl/ReadaloudLinkRepositoryImpl (core:data commonMain),
// backed by the DAOs added in #1057.

internal object IosNoOpReadaloudSidecarDownloads : ReadaloudSidecarDownloads {
    override fun listCached() = emptyList<ReadaloudSidecarDownloads.CachedSidecar>()
    override fun clearAll() {}
    override fun remove(storytellerSourceId: String, storytellerBookId: String) {}
}

// ── Audiobook player extras ──────────────────────────────────────────────────────────────────────

// IosNoOpAudioPlaybackPreferencesStore / IosNoOpAudioIdentityResolver / IosNoOpContentCacheAccessStore
// removed (issue #1065): iOS now binds the real AudioPlaybackPreferencesStoreImpl /
// AudioIdentityResolverImpl (both already commonMain) and IosContentCacheAccessStoreImpl.

internal object IosNoOpReaderSyncFactory : com.riffle.feature.reader.ReaderSyncFactoryInterface {
    override suspend fun createIfApplicable(itemId: String): com.riffle.feature.reader.ReaderSyncCoordinatorInterface? = null
    override suspend fun createAudiobookFollowIfApplicable(itemId: String): com.riffle.feature.reader.AudiobookFollowInterface? = null
}

internal object IosNoOpBundleAudiobookSource : com.riffle.core.domain.BundleAudiobookSource {
    override suspend fun localSession(sourceId: String, itemId: String): com.riffle.core.domain.AudiobookSession? = null
    override fun isAvailableOffline(sourceId: String, itemId: String): Boolean = false
}

internal object IosNoOpReadaloudHandoff : com.riffle.feature.player.ReadaloudHandoff {
    override fun preWarmSeek(globalSec: Double) = Unit
    override fun cancelPreWarm() = Unit
}

// IosNoOpAudioSyncPositionStore / IosNoOpReadingSyncPositionStore removed (issue #1065): iOS now
// binds the real ReadingPositionStoreImpl/AudiobookPositionStoreImpl as SyncPositionStore in
// iosDataModule (core:data), matching Android's wiring.
