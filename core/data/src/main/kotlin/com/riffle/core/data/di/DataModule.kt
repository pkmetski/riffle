package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.AppUpdateRepositoryImpl
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
import com.riffle.core.data.AnnotationSyncConfigStoreImpl
import com.riffle.core.data.EncryptedKeyValueStore
import com.riffle.core.data.KeystoreEncryptedKeyValueStore
import com.riffle.core.data.LastOpenedLibraryStoreImpl
import com.riffle.core.data.LibraryOrderPreferencesStoreImpl
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
import com.riffle.core.data.AudiobookBookmarkStoreImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ServerFilesCleanerImpl
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.ToReadRepositoryImpl
import com.riffle.core.data.CoverGridDensityStoreImpl
import com.riffle.core.data.AppThemeStoreImpl
import com.riffle.core.data.VolumeKeyPreferencesStoreImpl
import com.riffle.core.data.ReadingSpeedStoreImpl
import com.riffle.core.data.WakeLockPreferencesStoreImpl
import com.riffle.core.data.ReadaloudPreferencesStoreImpl
import com.riffle.core.data.ListeningPreferencesStoreImpl
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AnnotationSyncConfigStore
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
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.data.AudiobookBundleDownloader
import com.riffle.core.data.AudiobookChapterCacheRepositoryImpl
import com.riffle.core.data.ReadaloudAudioRepositoryImpl
import com.riffle.core.data.OfflineAvailabilitySnapshot
import com.riffle.core.data.StorytellerBundleAudiobookSource
import com.riffle.core.data.readaloudLinksByAbsItemKey
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.TocRepositoryImpl
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
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashReportDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FormattingPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryVisibilityPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryOrderPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LastOpenedLibraryDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WakeLockPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VolumeKeyPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppThemePreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoverGridDensityDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceIdDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceLabelDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadaloudPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadingSpeedDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ListeningPreferencesDataStore

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
    abstract fun bindAbsBookmarkApi(impl: AbsApiClient): com.riffle.core.network.AbsBookmarkApi

    @Binds
    @Singleton
    abstract fun bindStorytellerApi(impl: StorytellerApiClient): StorytellerApi

    @Binds
    @Singleton
    abstract fun bindStorytellerLibraryApi(impl: StorytellerApiClient): StorytellerLibraryApi

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(impl: AppUpdateRepositoryImpl): com.riffle.core.domain.AppUpdateRepository

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
    abstract fun bindAudiobookBookmarkStore(impl: AudiobookBookmarkStoreImpl): AudiobookBookmarkStore

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
    abstract fun bindLibraryOrderPreferencesStore(impl: LibraryOrderPreferencesStoreImpl): LibraryOrderPreferencesStore

    @Binds
    @Singleton
    abstract fun bindLastOpenedLibraryStore(impl: LastOpenedLibraryStoreImpl): LastOpenedLibraryStore

    @Binds
    @Singleton
    abstract fun bindWakeLockPreferencesStore(impl: WakeLockPreferencesStoreImpl): WakeLockPreferencesStore

    @Binds
    @Singleton
    abstract fun bindListeningPreferencesStore(impl: ListeningPreferencesStoreImpl): ListeningPreferencesStore

    @Binds
    @Singleton
    abstract fun bindVolumeKeyPreferencesStore(impl: VolumeKeyPreferencesStoreImpl): VolumeKeyPreferencesStore

    @Binds
    @Singleton
    abstract fun bindAppThemeStore(impl: AppThemeStoreImpl): AppThemeStore

    @Binds
    @Singleton
    abstract fun bindCoverGridDensityStore(impl: CoverGridDensityStoreImpl): CoverGridDensityStore

    @Binds
    @Singleton
    abstract fun bindReadaloudPreferencesStore(impl: ReadaloudPreferencesStoreImpl): ReadaloudPreferencesStore

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(impl: ConnectivityObserverImpl): ConnectivityObserver

    @Binds
    @Singleton
    abstract fun bindDeviceIdStore(impl: DeviceIdStoreImpl): DeviceIdStore

    @Binds
    @Singleton
    abstract fun bindDeviceLabelStore(impl: com.riffle.core.data.DeviceLabelStoreImpl): com.riffle.core.domain.DeviceLabelStore

    @Binds
    @Singleton
    abstract fun bindDeviceLabelResolver(impl: com.riffle.core.data.AndroidDeviceLabelResolver): com.riffle.core.domain.DeviceLabelResolver

    @Binds
    @Singleton
    abstract fun bindAnnotationStore(impl: AnnotationStoreImpl): AnnotationStore

    @Binds
    @Singleton
    abstract fun bindEncryptedKeyValueStore(impl: KeystoreEncryptedKeyValueStore): EncryptedKeyValueStore

    @Binds
    @Singleton
    abstract fun bindAnnotationSyncConfigStore(impl: AnnotationSyncConfigStoreImpl): AnnotationSyncConfigStore

    @Binds
    @Singleton
    abstract fun bindReadingSpeedStore(impl: ReadingSpeedStoreImpl): ReadingSpeedStore

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

    @Binds
    @Singleton
    abstract fun bindTocRepository(impl: TocRepositoryImpl): TocRepository

    @Binds
    @Singleton
    abstract fun bindAudiobookChapterCacheRepository(impl: AudiobookChapterCacheRepositoryImpl): AudiobookChapterCacheRepository

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
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideGitHubReleaseApi(okHttpClient: OkHttpClient): com.riffle.core.network.GitHubReleaseApi =
            com.riffle.core.network.GitHubReleaseApi(okHttpClient)

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
        @CrashReportDir
        fun provideCrashReportDir(@ApplicationContext context: Context): File =
            File(context.filesDir, "crash_reports").apply { mkdirs() }

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
        fun provideStorytellerSidecarFetcher(
            api: StorytellerBundleApiImpl,
            @ApplicationContext context: Context,
        ): com.riffle.core.data.StorytellerSidecarFetcher =
            // Fast path: bounded streaming GET (sidecarStreamClient, 240 s callTimeout) stops at the
            // first audio entry. Full-download fallback: unbounded downloadBundle client, used only when
            // the fast path finds no SMIL before audio (non-standard bundle ordering — ADR 0028).
            com.riffle.core.data.StorytellerSidecarFetcher(
                bundleApi = { url, bookId, token, insecure -> api.streamSidecar(url, bookId, token, insecure) },
                fullBundleApi = { url, bookId, token, insecure -> api.downloadBundle(url, bookId, token, insecure) },
                tempDir = { context.cacheDir },
            )

        @Provides
        @Singleton
        fun provideReadaloudSidecarPrefetcher(store: com.riffle.core.data.ReadaloudSidecarStore): com.riffle.core.data.ReadaloudSidecarPrefetcher =
            store

        @Provides
        @Singleton
        fun provideReadaloudSidecarCache(store: com.riffle.core.data.ReadaloudSidecarStore): com.riffle.core.domain.ReadaloudSidecarCache =
            store

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
            applicationScope: ApplicationScope,
        ): com.riffle.core.domain.BundleAudiobookSource =
            StorytellerBundleAudiobookSource(
                readaloudLinkRepository = readaloudLinkRepository,
                readaloudAudioRepository = readaloudAudioRepository,
                linksByAbsItem = OfflineAvailabilitySnapshot(
                    applicationScope = applicationScope,
                    source = readaloudLinkRepository.observeAll().map(::readaloudLinksByAbsItemKey),
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
        @LibraryOrderPreferencesDataStore
        fun provideLibraryOrderPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.libraryOrderPreferencesDataStore

        @Provides
        @Singleton
        @LastOpenedLibraryDataStore
        fun provideLastOpenedLibraryDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.lastOpenedLibraryDataStore

        @Provides
        @Singleton
        @WakeLockPreferencesDataStore
        fun provideWakeLockPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.wakeLockPreferencesDataStore

        @Provides
        @Singleton
        @ListeningPreferencesDataStore
        fun provideListeningPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.listeningPreferencesDataStore

        @Provides
        @Singleton
        @VolumeKeyPreferencesDataStore
        fun provideVolumeKeyPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.volumeKeyPreferencesDataStore

        @Provides
        @Singleton
        @AppThemePreferencesDataStore
        fun provideAppThemePreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.appThemePreferencesDataStore

        @Provides
        @Singleton
        @CoverGridDensityDataStore
        fun provideCoverGridDensityDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.coverGridDensityDataStore

        @Provides
        @Singleton
        @DeviceIdDataStore
        fun provideDeviceIdDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.deviceIdDataStore

        @Provides
        @Singleton
        @DeviceLabelDataStore
        fun provideDeviceLabelDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.deviceLabelDataStore

        @Provides
        @Singleton
        @ReadaloudPreferencesDataStore
        fun provideReadaloudPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.readaloudPreferencesDataStore

        @Provides
        @Singleton
        @ReadingSpeedDataStore
        fun provideReadingSpeedDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.readingSpeedDataStore

        @Provides
        @Singleton
        fun provideAnnotationMergeService(): com.riffle.core.domain.AnnotationMergeService =
            com.riffle.core.domain.AnnotationMergeService()

        @Provides
        @Singleton
        fun provideAnnotationSyncTargetHolder(
            configStore: com.riffle.core.domain.AnnotationSyncConfigStore,
            factory: com.riffle.core.data.WebDavAnnotationSyncTargetFactory,
        ): com.riffle.core.data.AnnotationSyncTargetHolder =
            com.riffle.core.data.AnnotationSyncTargetHolder(
                configStore = configStore,
                factory = factory,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
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
            libraryItemDao: com.riffle.core.database.LibraryItemDao,
        ): com.riffle.core.data.AnnotationSyncController =
            com.riffle.core.data.AnnotationSyncController(
                targetProvider = { holder.current() },
                mergeService = mergeService,
                annotationDao = annotationDao,
                deviceIdStore = deviceIdStore,
                deviceLabelResolver = deviceLabelResolver,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                ),
                statusStore = statusStore,
                sweepEnqueuer = sweepEnqueuer,
                usernameProvider = { sid -> serverRepository.getById(sid)?.username },
                bookTitleProvider = { sid, itemId ->
                    libraryItemDao.getById(sid, itemId)?.title?.takeIf { it.isNotBlank() }
                },
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
            libraryItemDao: com.riffle.core.database.LibraryItemDao,
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
