package com.riffle.core.data.di.modules

import com.riffle.core.data.AnnotationSyncConfigStoreImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.StorytellerLibraryApi
import com.riffle.core.network.StorytellerPositionApi
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
    abstract fun bindDirtyProgressLedger(impl: com.riffle.core.data.RoomDirtyProgressLedger): com.riffle.core.data.DirtyProgressLedger

    @Binds
    @Singleton
    abstract fun bindDirtyAnnotationLedger(impl: com.riffle.core.data.RoomDirtyAnnotationLedger): com.riffle.core.data.DirtyAnnotationLedger

    @Binds
    @Singleton
    abstract fun bindProgressRemoteFactory(impl: com.riffle.core.data.AbsProgressRemoteFactory): com.riffle.core.data.ProgressRemoteFactory

    @Binds
    @Singleton
    abstract fun bindReadaloudLinkReconciler(impl: ReadaloudMatchingService): com.riffle.core.domain.ReadaloudLinkReconciler

    @Binds
    @Singleton
    abstract fun bindStorytellerReadaloudCacheSyncer(impl: StorytellerReadaloudSyncer): com.riffle.core.domain.StorytellerReadaloudCacheSyncer

    companion object {
        // Durable offline progress reconcile (ADR 0030): resolve a serverId to its server+token
        // (null ⇒ skip), and assemble the multi-server dirty sweep over the single-target primitive.
        @Provides
        @Singleton
        fun provideServerTokenResolver(
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): com.riffle.core.data.ServerTokenResolver =
            com.riffle.core.data.ServerTokenResolver { serverId ->
                val server = serverRepository.getById(serverId) ?: return@ServerTokenResolver null
                val token = tokenStorage.getToken(serverId) ?: return@ServerTokenResolver null
                server to token
            }

        @Provides
        @Singleton
        fun provideProgressSweep(
            ledger: com.riffle.core.data.DirtyProgressLedger,
            resolver: com.riffle.core.data.ServerTokenResolver,
            remoteFactory: com.riffle.core.data.ProgressRemoteFactory,
            locks: com.riffle.core.data.ReconcileLocks,
            openTargets: com.riffle.core.data.OpenReconcileTargets,
            ebookStore: ReadingPositionStoreImpl,
            audioStore: AudiobookPositionStoreImpl,
            bookmarkDao: com.riffle.core.database.AudiobookBookmarkDao,
            bookmarkReconciler: com.riffle.core.data.AudiobookBookmarkReconciler,
        ): com.riffle.core.data.ProgressSweep =
            com.riffle.core.data.ProgressSweep(
                ledger, resolver,
                com.riffle.core.domain.ProgressReconciler(ebookStore),
                com.riffle.core.domain.ProgressReconciler(audioStore),
                remoteFactory, locks, openTargets,
                // Bookmarks ride the sweep at the same cadence as positions: enumerate dirty
                // (server, item) pairs straight off the bookmark DAO (ADR 0030, Task 12).
                object : com.riffle.core.data.DirtyBookmarkLedger {
                    override suspend fun serversWithDirty() = bookmarkDao.serversWithDirtyRows()
                    override suspend fun dirtyItems(serverId: String) =
                        bookmarkDao.dirtyForServer(serverId).map { it.itemId }.distinct()
                },
                com.riffle.core.data.BookmarkReconcile { serverId, itemId, baseUrl, token, insecureAllowed ->
                    bookmarkReconciler.reconcile(serverId, itemId, baseUrl, token, insecureAllowed)
                },
            )

        @Provides
        @Singleton
        fun provideStorytellerPositionSyncController(
            api: StorytellerPositionApi,
            positionStore: ReadingPositionStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): StorytellerPositionSyncController =
            StorytellerPositionSyncController(api, positionStore, serverRepository, tokenStorage)

        @Provides
        @Singleton
        fun provideAnnotationMergeService(): com.riffle.core.domain.AnnotationMergeService =
            com.riffle.core.domain.AnnotationMergeService()

        @Provides
        @Singleton
        fun provideAnnotationSyncTargetHolder(
            configStore: AnnotationSyncConfigStore,
            factory: com.riffle.core.data.WebDavAnnotationSyncTargetFactory,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
        ): com.riffle.core.data.AnnotationSyncTargetHolder =
            com.riffle.core.data.AnnotationSyncTargetHolder(
                configStore = configStore,
                factory = factory,
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
            statusStore: com.riffle.core.data.AnnotationSyncStatusStore,
            sweepEnqueuer: com.riffle.core.domain.AnnotationSweepEnqueuer,
            serverRepository: ServerRepository,
            libraryItemDao: LibraryItemDao,
            locks: com.riffle.core.data.ReconcileLocks,
            sentinelWriter: com.riffle.core.data.DeviceMetaSentinelWriter,
            dispatchers: com.riffle.core.domain.DispatcherProvider,
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
                usernameProvider = { sid -> serverRepository.getById(sid)?.username },
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
            serverRepository: ServerRepository,
            statusStore: com.riffle.core.data.AnnotationSyncStatusStore,
            libraryItemDao: LibraryItemDao,
            dirtyLedger: com.riffle.core.data.DirtyAnnotationLedger,
            locks: com.riffle.core.data.ReconcileLocks,
            sentinelWriter: com.riffle.core.data.DeviceMetaSentinelWriter,
        ): com.riffle.core.data.AnnotationSweep =
            com.riffle.core.data.AnnotationSweep(
                targetProvider = { holder.current() },
                annotationDao = annotationDao,
                deviceIdStore = deviceIdStore,
                deviceLabelResolver = deviceLabelResolver,
                serverRepository = serverRepository,
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
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
            storytellerApi: StorytellerLibraryApi,
            libraryItemDao: LibraryItemDao,
        ): StorytellerReadaloudSyncer = StorytellerReadaloudSyncer(
            serverRepository = serverRepository,
            tokenStorage = tokenStorage,
            storytellerApi = storytellerApi,
            libraryItemDao = libraryItemDao,
            clock = System::currentTimeMillis,
        )
    }
}
