package com.riffle.shared.library

import com.riffle.core.domain.ReadaloudSidecarDownloads

// IosNoOpApplicationScope removed (issue #1065): iOS now binds the shared
// DefaultApplicationScope (core:domain commonMain) — the stub's withSurvivable ran the block
// inline in the caller's context, so terminal writes were cancelled with the ViewModel.

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

// IosNoOpStorytellerSyncer / IosNoOpReadaloudReconciler removed (issue #1065): iOS now binds
// StorytellerReadaloudSyncer and ReadaloudMatchingService (core:data commonMain, previously
// Android-only) against the readaloud DAOs from #1057.
