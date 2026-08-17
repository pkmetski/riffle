package com.riffle.core.data.di.modules

import com.riffle.core.common.Clock
import com.riffle.core.common.RandomProvider
import com.riffle.core.data.CatalogSyncSourceResolver
import com.riffle.core.data.AnnotationSyncConfigStoreImpl
import com.riffle.core.data.AudiobookBookmarkSyncStoreImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.StorytellerLibraryApi
import com.riffle.core.network.StorytellerPositionApi
import com.riffle.core.sync.BookmarkReconcile
import com.riffle.core.sync.DirtyAnnotationLedger
import com.riffle.core.sync.DirtyBookmarkLedger
import com.riffle.core.sync.DirtyProgressLedger
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.core.sync.ProgressRemoteFactory
import com.riffle.core.sync.ProgressSweep
import com.riffle.core.sync.ReconcileLocks
import com.riffle.core.sync.SyncSourceResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindAnnotationSyncConfigStore(impl: AnnotationSyncConfigStoreImpl): AnnotationSyncConfigStore

    @Binds
    @Singleton
    abstract fun bindEbookSyncPositionStore(impl: ReadingPositionStoreImpl): com.riffle.core.domain.SyncPositionStore<String>

    @Binds
    @Singleton
    abstract fun bindAudioSyncPositionStore(impl: AudiobookPositionStoreImpl): com.riffle.core.domain.SyncPositionStore<Double>

    @Binds
    @Singleton
    abstract fun bindDirtyProgressLedger(impl: com.riffle.core.data.RoomDirtyProgressLedger): DirtyProgressLedger

    @Binds
    @Singleton
    abstract fun bindDirtyAnnotationLedger(impl: com.riffle.core.data.RoomDirtyAnnotationLedger): DirtyAnnotationLedger

    @Binds
    @Singleton
    abstract fun bindProgressRemoteFactory(impl: com.riffle.core.data.CatalogProgressRemoteFactory): ProgressRemoteFactory

    @Binds
    @Singleton
    abstract fun bindSyncSourceResolver(impl: CatalogSyncSourceResolver): SyncSourceResolver

    @Binds
    @Singleton
    abstract fun bindItemProgressPuller(impl: com.riffle.core.data.ReconcilingItemProgressPuller): com.riffle.core.data.ItemProgressPuller

    @Binds
    @Singleton
    abstract fun bindReadaloudLinkReconciler(impl: ReadaloudMatchingService): com.riffle.core.domain.ReadaloudLinkReconciler

    @Binds
    @Singleton
    abstract fun bindStorytellerReadaloudCacheSyncer(impl: StorytellerReadaloudSyncer): com.riffle.core.domain.StorytellerReadaloudCacheSyncer

    @Binds
    @Singleton
    abstract fun bindAudiobookBookmarkSyncStore(impl: AudiobookBookmarkSyncStoreImpl): AudiobookBookmarkSyncStore

    companion object {
        // Durable offline progress reconcile (ADR 0036): assemble the multi-source dirty sweep
        // over the single-target primitive. Skipping unresolvable sources (no row / no token /
        // no factory) is baked into ProgressSweep via SyncSourceResolver.
        @Provides
        @Singleton
        fun provideProgressSweep(
            ledger: DirtyProgressLedger,
            sourceResolver: SyncSourceResolver,
            remoteFactory: ProgressRemoteFactory,
            locks: ReconcileLocks,
            openTargets: OpenReconcileTargets,
            ebookStore: ReadingPositionStoreImpl,
            audioStore: AudiobookPositionStoreImpl,
            bookmarkDao: com.riffle.core.database.AudiobookBookmarkDao,
            bookmarkReconciler: com.riffle.core.sync.AudiobookBookmarkReconciler,
            uiProgressSink: com.riffle.core.data.LibraryItemUiProgressSink,
        ): ProgressSweep =
            ProgressSweep(
                ledger,
                sourceResolver,
                com.riffle.core.domain.ProgressReconciler(ebookStore, uiProgressSink),
                com.riffle.core.domain.ProgressReconciler(audioStore, uiProgressSink),
                remoteFactory, locks, openTargets,
                // Bookmarks ride the sweep at the same cadence as positions: enumerate dirty
                // (source, item) pairs straight off the bookmark DAO (ADR 0036, Task 12).
                object : DirtyBookmarkLedger {
                    override suspend fun serversWithDirty() = bookmarkDao.sourcesWithDirtyRows()
                    override suspend fun dirtyItems(sourceId: String) =
                        bookmarkDao.dirtyForSource(sourceId).map { it.itemId }.distinct()
                },
                BookmarkReconcile { sourceId, itemId ->
                    bookmarkReconciler.reconcile(sourceId, itemId)
                },
            )

        @Provides
        @Singleton
        fun provideReconcileLocks(): ReconcileLocks = ReconcileLocks()

        @Provides
        @Singleton
        fun provideOpenReconcileTargets(): OpenReconcileTargets = OpenReconcileTargets()

        @Provides
        @Singleton
        fun provideAnnotationSyncStatusStore(): com.riffle.core.sync.AnnotationSyncStatusStore =
            com.riffle.core.sync.AnnotationSyncStatusStore()

        @Provides
        @Singleton
        fun provideAudiobookBookmarkReconciler(
            store: AudiobookBookmarkSyncStore,
            sourceResolver: SyncSourceResolver,
            clock: Clock,
            random: RandomProvider,
        ): com.riffle.core.sync.AudiobookBookmarkReconciler =
            com.riffle.core.sync.AudiobookBookmarkReconciler(
                store = store,
                sourceResolver = sourceResolver,
                clock = clock,
                random = random,
            )

        @Provides
        @Singleton
        fun provideStorytellerPositionSyncController(
            api: StorytellerPositionApi,
            positionStore: ReadingPositionStore,
            sourceRepository: SourceRepository,
            tokenStorage: TokenStorage,
        ): StorytellerPositionSyncController =
            StorytellerPositionSyncController(api, positionStore, sourceRepository, tokenStorage)

        @Provides
        @Singleton
        fun provideAnnotationMergeService(): com.riffle.core.domain.AnnotationMergeService =
            com.riffle.core.domain.AnnotationMergeService()

        @Provides
        @Singleton
        fun provideWebDavAnnotationSyncTargetFactory(
            httpClient: io.ktor.client.HttpClient,
            dispatchers: DispatcherProvider,
        ): com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory =
            com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory(httpClient, dispatchers)

        @Provides
        @Singleton
        fun provideAnnotationSyncTargetHolder(
            configStore: AnnotationSyncConfigStore,
            webDavFactory: com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory,
            absBookmarkFactory: com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory,
            sourceRepository: SourceRepository,
            dispatchers: DispatcherProvider,
        ): com.riffle.core.data.AnnotationSyncTargetHolder =
            com.riffle.core.data.AnnotationSyncTargetHolder(
                configStore = configStore,
                webDavFactory = webDavFactory,
                absBookmarkFactory = absBookmarkFactory,
                sourceRepository = sourceRepository,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + dispatchers.io,
                ),
            )

        @Provides
        @Singleton
        fun provideAnnotationSyncController(
            holder: com.riffle.core.data.AnnotationSyncTargetHolder,
            mergeService: com.riffle.core.domain.AnnotationMergeService,
            annotationDao: com.riffle.core.database.AnnotationDao,
            deviceIdStore: com.riffle.core.domain.DeviceIdStore,
            deviceLabelResolver: com.riffle.core.domain.DeviceLabelResolver,
            statusStore: com.riffle.core.sync.AnnotationSyncStatusStore,
            sweepEnqueuer: com.riffle.core.domain.AnnotationSweepEnqueuer,
            sourceRepository: SourceRepository,
            libraryItemDao: LibraryItemDao,
            locks: ReconcileLocks,
            sentinelWriter: com.riffle.core.data.DeviceMetaSentinelWriter,
            dispatchers: DispatcherProvider,
        ): com.riffle.core.data.AnnotationSyncController =
            com.riffle.core.data.AnnotationSyncController(
                targetProvider = { holder.current() },
                mergeService = mergeService,
                annotationDao = annotationDao,
                deviceIdStore = deviceIdStore,
                deviceLabelResolver = deviceLabelResolver,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + dispatchers.io,
                ),
                statusStore = statusStore,
                sweepEnqueuer = sweepEnqueuer,
                usernameProvider = { sid -> sourceRepository.getById(sid)?.username },
                bookTitleProvider = { sid, itemId ->
                    libraryItemDao.getById(sid, itemId)?.title?.takeIf { it.isNotBlank() }
                },
                locks = locks,
                sentinelWriter = sentinelWriter,
            )

        @Provides
        @Singleton
        fun provideAnnotationSweep(
            holder: com.riffle.core.data.AnnotationSyncTargetHolder,
            annotationDao: com.riffle.core.database.AnnotationDao,
            deviceIdStore: com.riffle.core.domain.DeviceIdStore,
            deviceLabelResolver: com.riffle.core.domain.DeviceLabelResolver,
            sourceRepository: SourceRepository,
            statusStore: com.riffle.core.sync.AnnotationSyncStatusStore,
            libraryItemDao: LibraryItemDao,
            dirtyLedger: DirtyAnnotationLedger,
            locks: ReconcileLocks,
            sentinelWriter: com.riffle.core.data.DeviceMetaSentinelWriter,
        ): com.riffle.core.data.AnnotationSweep =
            com.riffle.core.data.AnnotationSweep(
                targetProvider = { holder.current() },
                annotationDao = annotationDao,
                deviceIdStore = deviceIdStore,
                deviceLabelResolver = deviceLabelResolver,
                sourceRepository = sourceRepository,
                statusStore = statusStore,
                bookTitleProvider = { sid, itemId ->
                    libraryItemDao.getById(sid, itemId)?.title?.takeIf { it.isNotBlank() }
                },
                dirtyLedger = dirtyLedger,
                locks = locks,
                sentinelWriter = sentinelWriter,
            )

        @Provides
        @Singleton
        fun provideAnnotationSyncMaintenance(
            holder: com.riffle.core.data.AnnotationSyncTargetHolder,
        ): com.riffle.core.data.AnnotationSyncMaintenance =
            com.riffle.core.data.AnnotationSyncMaintenance(targetProvider = { holder.current() })

        @Provides
        @Singleton
        fun provideStorytellerReadaloudSyncer(
            sourceRepository: SourceRepository,
            tokenStorage: TokenStorage,
            storytellerApi: StorytellerLibraryApi,
            libraryItemDao: LibraryItemDao,
        ): StorytellerReadaloudSyncer = StorytellerReadaloudSyncer(
            sourceRepository = sourceRepository,
            tokenStorage = tokenStorage,
            storytellerApi = storytellerApi,
            libraryItemDao = libraryItemDao,
            clock = System::currentTimeMillis,
        )
    }
}
