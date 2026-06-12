package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.AudioIdentityResolverImpl
import com.riffle.core.data.AudioPlaybackPreferencesStoreImpl
import com.riffle.core.data.BookFormattingPreferencesStoreImpl
import com.riffle.core.data.ConnectivityObserverImpl
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.DeviceIdStoreImpl
import com.riffle.core.data.DownloadsRepositoryImpl
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.FormattingPreferencesStoreImpl
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.AudiobookDownloadRepositoryImpl
import com.riffle.core.data.AudiobookRepositoryImpl
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.data.LibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.LocalStoreImpl
import com.riffle.core.data.LocalStoreMigrator
import com.riffle.core.data.PdfRepositoryImpl
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.CrossEpubIndexBuilderService
import com.riffle.core.data.CrossEpubIndexStoreImpl
import com.riffle.core.data.ReadaloudLinkRepositoryImpl
import com.riffle.core.data.ReadaloudReviewRepositoryImpl
import com.riffle.core.data.ReadaloudResumeStoreImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ServerFilesCleanerImpl
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.ToReadRepositoryImpl
import com.riffle.core.data.VolumeKeyPreferencesStoreImpl
import com.riffle.core.data.WakeLockPreferencesStoreImpl
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.ServerFilesCleaner
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.data.AudiobookBundleDownloader
import com.riffle.core.data.ReadaloudAudioRepositoryImpl
import com.riffle.core.data.StorytellerBundleAudiobookSource
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.network.AudiobookBundleApiImpl
import com.riffle.core.network.StorytellerPositionApi
import com.riffle.core.network.StorytellerPositionApiImpl
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.StorytellerApiClient
import com.riffle.core.network.StorytellerBundleApiImpl
import com.riffle.core.network.StorytellerLibraryApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashReportFile

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FormattingPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryVisibilityPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WakeLockPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VolumeKeyPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceIdDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubCacheStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubDownloadsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudiobookDownloadsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfCacheStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfDownloadsStore

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindAudiobookRepository(impl: AudiobookRepositoryImpl): AudiobookRepository

    @Binds
    @Singleton
    abstract fun bindAudiobookDownloadRepository(impl: AudiobookDownloadRepositoryImpl): AudiobookDownloadRepository

    @Binds
    @Singleton
    abstract fun bindToReadRepository(impl: ToReadRepositoryImpl): ToReadRepository

    @Binds
    @Singleton
    abstract fun bindReadaloudLinkRepository(impl: ReadaloudLinkRepositoryImpl): ReadaloudLinkRepository

    @Binds
    @Singleton
    abstract fun bindReadaloudReviewRepository(impl: ReadaloudReviewRepositoryImpl): ReadaloudReviewRepository

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: KeystoreTokenStorage): TokenStorage

    @Binds
    @Singleton
    abstract fun bindCrashReportRepository(impl: CrashReportRepositoryImpl): CrashReportRepository

    @Binds
    @Singleton
    abstract fun bindAbsApi(impl: AbsApiClient): AbsApi

    @Binds
    @Singleton
    abstract fun bindAbsLibraryApi(impl: AbsApiClient): AbsLibraryApi

    @Binds
    @Singleton
    abstract fun bindAbsSessionApi(impl: AbsApiClient): AbsSessionApi

    @Binds
    @Singleton
    abstract fun bindAbsServerInfoApi(impl: AbsApiClient): AbsServerInfoApi

    @Binds
    @Singleton
    abstract fun bindAbsPlaybackApi(impl: AbsApiClient): AbsPlaybackApi

    @Binds
    @Singleton
    abstract fun bindStorytellerApi(impl: StorytellerApiClient): StorytellerApi

    @Binds
    @Singleton
    abstract fun bindStorytellerLibraryApi(impl: StorytellerApiClient): StorytellerLibraryApi

    @Binds
    @Singleton
    abstract fun bindCrossEpubIndexStore(impl: CrossEpubIndexStoreImpl): CrossEpubIndexStore

    @Binds
    @Singleton
    abstract fun bindCrossEpubIndexBuildTrigger(impl: CrossEpubIndexBuilderService): CrossEpubIndexBuildTrigger

    @Binds
    @Singleton
    abstract fun bindReadingPositionStore(impl: ReadingPositionStoreImpl): ReadingPositionStore

    @Binds
    @Singleton
    abstract fun bindAudiobookPositionStore(impl: AudiobookPositionStoreImpl): AudiobookPositionStore

    @Binds
    @Singleton
    abstract fun bindReadaloudResumeStore(impl: ReadaloudResumeStoreImpl): ReadaloudResumeStore

    @Binds
    @Singleton
    abstract fun bindReadingSessionRepository(impl: ReadingSessionRepositoryImpl): ReadingSessionRepository

    @Binds
    @Singleton
    abstract fun bindFormattingPreferencesStore(impl: FormattingPreferencesStoreImpl): FormattingPreferencesStore

    @Binds
    @Singleton
    abstract fun bindBookFormattingPreferencesStore(impl: BookFormattingPreferencesStoreImpl): BookFormattingPreferencesStore

    @Binds
    @Singleton
    abstract fun bindAudioPlaybackPreferencesStore(impl: AudioPlaybackPreferencesStoreImpl): AudioPlaybackPreferencesStore

    @Binds
    @Singleton
    abstract fun bindAudioIdentityResolver(impl: AudioIdentityResolverImpl): AudioIdentityResolver

    @Binds
    @Singleton
    abstract fun bindLibraryVisibilityPreferencesStore(impl: LibraryVisibilityPreferencesStoreImpl): LibraryVisibilityPreferencesStore

    @Binds
    @Singleton
    abstract fun bindWakeLockPreferencesStore(impl: WakeLockPreferencesStoreImpl): WakeLockPreferencesStore

    @Binds
    @Singleton
    abstract fun bindVolumeKeyPreferencesStore(impl: VolumeKeyPreferencesStoreImpl): VolumeKeyPreferencesStore

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(impl: ConnectivityObserverImpl): ConnectivityObserver

    @Binds
    @Singleton
    abstract fun bindDeviceIdStore(impl: DeviceIdStoreImpl): DeviceIdStore

    @Binds
    @Singleton
    abstract fun bindAnnotationStore(impl: AnnotationStoreImpl): AnnotationStore

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
    abstract fun bindProgressRemoteFactory(impl: com.riffle.core.data.AbsProgressRemoteFactory): com.riffle.core.data.ProgressRemoteFactory

    companion object {
        // Durable offline progress reconcile (ADR 0030): resolve a serverId to its server+token
        // (null ⇒ skip), and assemble the multi-server dirty sweep over the single-target primitive.
        @Provides
        @Singleton
        fun provideServerTokenResolver(
            serverRepository: com.riffle.core.domain.ServerRepository,
            tokenStorage: com.riffle.core.domain.TokenStorage,
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
            locks: com.riffle.core.data.ProgressSyncLocks,
            openTargets: com.riffle.core.data.OpenReconcileTargets,
            ebookStore: ReadingPositionStoreImpl,
            audioStore: AudiobookPositionStoreImpl,
        ): com.riffle.core.data.ProgressSweep =
            com.riffle.core.data.ProgressSweep(
                ledger, resolver,
                com.riffle.core.domain.ProgressReconciler(ebookStore),
                com.riffle.core.domain.ProgressReconciler(audioStore),
                remoteFactory, locks, openTargets,
            )

        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideAbsApiClient(okHttpClient: OkHttpClient): AbsApiClient =
            AbsApiClient(okHttpClient)

        @Provides
        @Singleton
        fun provideStorytellerApiClient(okHttpClient: OkHttpClient): StorytellerApiClient =
            StorytellerApiClient(okHttpClient)

        @Provides
        @Singleton
        @CrashReportFile
        fun provideCrashReportFile(@ApplicationContext context: Context): File =
            File(context.filesDir, "crash_report.txt")

        @Provides
        @Singleton
        @EpubCacheStore
        fun provideEpubCacheStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.cacheDir.resolve("epubs").also { it.mkdirs() }, ".epub")

        @Provides
        @Singleton
        @EpubDownloadsStore
        fun provideEpubDownloadsStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.filesDir.resolve("downloads/epubs").also { it.mkdirs() }, ".epub")

        @Provides
        @Singleton
        @AudiobookDownloadsDir
        fun provideAudiobookDownloadsDir(@ApplicationContext context: Context): java.io.File =
            context.filesDir.resolve("downloads/audiobooks").also { it.mkdirs() }

        @Provides
        @Singleton
        @PdfCacheStore
        fun providePdfCacheStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.cacheDir.resolve("pdfs").also { it.mkdirs() }, ".pdf")

        @Provides
        @Singleton
        @PdfDownloadsStore
        fun providePdfDownloadsStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.filesDir.resolve("downloads/pdfs").also { it.mkdirs() }, ".pdf")

        // One-time relocation of legacy flat files into per-Server subdirectories (ADR 0025).
        @Provides
        @Singleton
        fun provideLocalStoreMigrator(
            @ApplicationContext context: Context,
            libraryItemDao: LibraryItemDao,
        ): LocalStoreMigrator =
            LocalStoreMigrator(
                stores = listOf(
                    context.cacheDir.resolve("epubs") to ".epub",
                    context.filesDir.resolve("downloads/epubs") to ".epub",
                    context.cacheDir.resolve("pdfs") to ".pdf",
                    context.filesDir.resolve("downloads/pdfs") to ".pdf",
                ),
                resolveServerId = { itemId -> libraryItemDao.findServerIdForItem(itemId) },
            )

        @Provides
        @Singleton
        fun provideStorytellerBundleApiImpl(okHttpClient: OkHttpClient): StorytellerBundleApiImpl =
            StorytellerBundleApiImpl(okHttpClient)

        @Provides
        @Singleton
        fun provideAudiobookBundleApi(okHttpClient: OkHttpClient): AudiobookBundleApiImpl =
            AudiobookBundleApiImpl(okHttpClient)

        @Provides
        @Singleton
        fun provideAudiobookBundleDownloader(
            @ApplicationContext context: Context,
            api: AudiobookBundleApiImpl,
        ): AudiobookBundleDownloader = AudiobookBundleDownloader(
            api = api,
            // Write into the same Downloads EPUB store the reader reads from — the synced bundle is
            // both the EPUB and the audio source (ADR 0023), so they share one file. Must mirror
            // LocalStoreImpl's dir/<serverId>/<id> layout so downloadsStore.get(serverId, id) finds it.
            targetFileProvider = { serverId, id ->
                context.filesDir.resolve("downloads/epubs").resolve(serverId).also { it.mkdirs() }.resolve("$id.epub")
            },
        )

        @Provides
        @Singleton
        fun provideStorytellerPositionApi(okHttpClient: OkHttpClient): StorytellerPositionApi =
            StorytellerPositionApiImpl(okHttpClient)

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
        fun provideReadaloudAudioRepository(
            downloader: AudiobookBundleDownloader,
            bundleProbe: StorytellerBundleApiImpl,
            @EpubCacheStore cacheStore: LocalStore,
            @EpubDownloadsStore downloadsStore: LocalStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): ReadaloudAudioRepository = ReadaloudAudioRepositoryImpl(
            downloader = downloader,
            bundleProbe = bundleProbe,
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            serverRepository = serverRepository,
            tokenStorage = tokenStorage,
        )

        @Provides
        @Singleton
        fun provideBundleAudiobookSource(
            readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
            readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
        ): com.riffle.core.domain.BundleAudiobookSource =
            StorytellerBundleAudiobookSource(
                readaloudLinkRepository = readaloudLinkRepository,
                readaloudAudioRepository = readaloudAudioRepository,
                applicationScope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                ),
            )

        @Provides
        @Singleton
        fun provideEpubRepository(
            api: AbsLibraryApi,
            @EpubCacheStore cacheStore: LocalStore,
            @EpubDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): EpubRepository = EpubRepositoryImpl(api, cacheStore, downloadsStore, positionStore, serverRepository, tokenStorage)

        @Provides
        @Singleton
        fun providePdfRepository(
            api: AbsLibraryApi,
            @PdfCacheStore cacheStore: LocalStore,
            @PdfDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): PdfRepository = PdfRepositoryImpl(api, cacheStore, downloadsStore, positionStore, serverRepository, tokenStorage)

        @Provides
        @Singleton
        fun provideLibraryItemOfflineAvailability(
            epubRepository: EpubRepository,
            pdfRepository: PdfRepository,
            audiobookDownloadRepository: AudiobookDownloadRepository,
            bundleAudiobookSource: com.riffle.core.domain.BundleAudiobookSource,
        ): LibraryItemOfflineAvailability =
            LibraryItemOfflineAvailability(epubRepository, pdfRepository, audiobookDownloadRepository, bundleAudiobookSource)

        @Provides
        @Singleton
        fun provideDownloadsRepository(
            @EpubCacheStore epubCacheStore: LocalStore,
            @EpubDownloadsStore epubDownloadsStore: LocalStore,
            @PdfCacheStore pdfCacheStore: LocalStore,
            @PdfDownloadsStore pdfDownloadsStore: LocalStore,
        ): DownloadsRepository = DownloadsRepositoryImpl(epubCacheStore, epubDownloadsStore, pdfCacheStore, pdfDownloadsStore)

        @Provides
        @Singleton
        fun provideServerFilesCleaner(
            @EpubCacheStore epubCacheStore: LocalStore,
            @EpubDownloadsStore epubDownloadsStore: LocalStore,
            @PdfCacheStore pdfCacheStore: LocalStore,
            @PdfDownloadsStore pdfDownloadsStore: LocalStore,
            @AudiobookDownloadsDir audiobookDownloadsDir: File,
        ): ServerFilesCleaner = ServerFilesCleanerImpl(
            stores = listOf(epubCacheStore, epubDownloadsStore, pdfCacheStore, pdfDownloadsStore),
            audiobookDownloadsDir = audiobookDownloadsDir,
        )

        @Provides
        @Singleton
        @FormattingPreferencesDataStore
        fun provideFormattingPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.formattingPreferencesDataStore

        @Provides
        @Singleton
        @LibraryVisibilityPreferencesDataStore
        fun provideLibraryVisibilityPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.libraryVisibilityPreferencesDataStore


        @Provides
        @Singleton
        @WakeLockPreferencesDataStore
        fun provideWakeLockPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.wakeLockPreferencesDataStore

        @Provides
        @Singleton
        @VolumeKeyPreferencesDataStore
        fun provideVolumeKeyPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.volumeKeyPreferencesDataStore

        @Provides
        @Singleton
        @DeviceIdDataStore
        fun provideDeviceIdDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.deviceIdDataStore

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
