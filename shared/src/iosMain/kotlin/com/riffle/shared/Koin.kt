package com.riffle.shared

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.DefaultCatalogRegistry
import com.riffle.core.catalog.komga.KomgaCatalogFactory
import com.riffle.core.common.Clock
import com.riffle.core.common.EncryptedKeyValueStore
import com.riffle.core.common.IosSystemClock
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.data.AnnotationSyncConfigStoreImpl
import com.riffle.core.data.AnnotationsLibraryRepositoryImpl
import com.riffle.core.data.AppearanceCoordinatorImpl
import com.riffle.core.data.AudioIdentityResolverImpl
import com.riffle.core.data.AudioPlaybackPreferencesStoreImpl
import com.riffle.core.data.AudiobookBookmarkStoreImpl
import com.riffle.core.data.AudiobookChapterCacheRepositoryImpl
import com.riffle.core.data.AudiobookRepositoryImpl
import com.riffle.core.data.IosAppUpdatePreferencesStoreImpl
import com.riffle.core.data.IosAudiobookCacheRepositoryImpl
import com.riffle.core.data.IosAudiobookDownloadRepositoryImpl
import com.riffle.core.data.IosAudiobookTrackDownloader
import com.riffle.core.data.IosContentCacheAccessStoreImpl
import com.riffle.core.data.IosEncryptedKeyValueStore
import com.riffle.core.data.IosLastOpenedLibraryStoreImpl
import com.riffle.core.data.IosLibraryItemOfflineAvailabilityImpl
import com.riffle.core.data.IosLibraryObserverImpl
import com.riffle.core.data.IosLibraryRefresherImpl
import com.riffle.core.data.IosLibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.IosPanelViewPreferencesStoreImpl
import com.riffle.core.data.IosPlaylistsRepositoryImpl
import com.riffle.core.data.IosSourceRepositoryImpl
import com.riffle.core.data.IosToReadRepositoryImpl
import com.riffle.core.data.LocalAvailabilityEventsImpl
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.PublicationMetricsRepositoryImpl
import com.riffle.core.data.ReadaloudLinkRepositoryImpl
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadaloudReviewRepositoryImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.TocRepositoryImpl
import com.riffle.core.data.comic.panel.GitHubPanelReportRepository
import com.riffle.core.data.di.iosDataModule
import com.riffle.core.data.di.iosDatabaseModule
import com.riffle.core.data.localfiles.IosLocalFilesFolderRepository
import com.riffle.core.data.localfiles.IosLocalFilesScanner
import com.riffle.core.data.localfiles.SaveLocalFileMetadataOverrideUseCase
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.DefaultApplicationScope
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudReviewMutator
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.domain.SystemTimeProvider
import com.riffle.core.domain.TimeProvider
import com.riffle.core.domain.TocRepository
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.WebSourceRegistry
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.domain.localfiles.LocalFilesFolderRepositoryInterface
import com.riffle.core.domain.localfiles.LocalFilesScannerInterface
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.ReadaloudReviewActions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.logging.iosLoggingModule
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.KomgaCbzApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.KomgaLibraryApiClient
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.StorytellerApiClient
import com.riffle.core.network.createDefaultHttpClient
import com.riffle.core.sources.SourceAdapter
import com.riffle.core.sources.abs.AbsSourceAdapter
import com.riffle.core.sources.komga.KomgaSourceAdapter
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.CollectionDetailViewModel
import com.riffle.feature.library.CoverImageCopier
import com.riffle.feature.library.DownloadManager
import com.riffle.feature.library.EpubTocExtractor
import com.riffle.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.LibrarySectionViewModel
import com.riffle.feature.library.LocalFileMetadataOverrideSaver
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.feature.library.ReadaloudOfflineDownloader
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.SeriesDetailViewModel
import com.riffle.feature.library.WebSourceLibraryItemUpserter
import com.riffle.feature.player.AudiobookHandoffState
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.AudiobookReconciliationCoordinator
import com.riffle.feature.player.AudiobookResumeResolver
import com.riffle.feature.player.FollowLoopOrchestrator
import com.riffle.feature.player.NowPlayingStore
import com.riffle.feature.player.ProgressSweepRunner
import com.riffle.feature.player.ReadaloudHandoff
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.ProgressFlushScope
import com.riffle.feature.reader.ReaderStateHolder
import com.riffle.feature.reader.VolumeKeyDispatcher
import com.riffle.feature.reader.VolumeNavigationController
import com.riffle.feature.settings.AppVersion
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.source.SourceSetupViewModel
import com.riffle.feature.source.SourceTypePickerViewModel
import com.riffle.feature.source.ui.AddSourceViewModel
import com.riffle.feature.source.ui.ComposeResourceSourceUiStrings
import com.riffle.feature.source.ui.DevSourceDefaults
import com.riffle.feature.source.ui.ProgressSyncTrigger
import com.riffle.feature.source.ui.SelectLibrariesViewModel
import com.riffle.feature.source.ui.SourceUiStrings
import com.riffle.feature.source.ui.WebdavConnectionTester
import com.riffle.feature.source.ui.WebdavTestOutcome
import com.riffle.shared.audiobook.IosAbsAudiobookRepository
import com.riffle.shared.audiobook.IosAudioPlayerBridgeFactory
import com.riffle.shared.audiobook.IosAudioPlayerController
import com.riffle.shared.library.IosContentCacheSettingsStoreImpl
import com.riffle.shared.library.IosCoverImageCopier
import com.riffle.shared.library.IosDownloadManagerImpl
import com.riffle.shared.library.IosDownloadsRepositoryImpl
import com.riffle.shared.library.IosEpubRepositoryImpl
import com.riffle.shared.library.IosNoOpBookImportManager
import com.riffle.shared.library.IosNoOpBundleAudiobookSource
import com.riffle.shared.library.IosNoOpCrossEpubIndexBuildTrigger
import com.riffle.shared.library.IosNoOpReadaloudAudioRepository
import com.riffle.shared.library.IosNoOpReadaloudHandoff
import com.riffle.shared.library.IosNoOpReadaloudOfflineDownloader
import com.riffle.shared.library.IosNoOpReadaloudSidecarDownloads
import com.riffle.shared.library.IosNoOpReadaloudSidecarPrefetcher
import com.riffle.shared.library.IosNoOpReaderSyncFactory
import com.riffle.shared.library.IosPdfPageCountExtractor
import com.riffle.shared.library.IosPdfRepositoryImpl
import com.riffle.shared.library.IosWebSourceLibraryItemUpserterImpl
import com.riffle.shared.reader.IosCbzDownloader
import com.riffle.shared.reader.IosCbzRepository
import com.riffle.shared.reader.IosEbookCfiTranslatorFactory
import com.riffle.shared.reader.IosEpubDownloader
import com.riffle.shared.reader.IosEpubNavigatorBridgeFactory
import com.riffle.shared.reader.IosEpubTocExtractor
import com.riffle.shared.reader.IosPdfDownloader
import com.riffle.shared.reader.IosPdfNavigatorBridgeFactory
import com.riffle.shared.reader.IosPublicationInspector
import com.riffle.shared.settings.IosNoOpAppUpdateRepository
import com.riffle.shared.settings.IosNoOpCrashReportRepository
import com.riffle.shared.settings.IosNoOpLocalFilesFolderHealthChecker
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.Foundation.NSBundle
import org.koin.core.context.startKoin as koinStartKoin

private fun iosLibraryModule(
    navigatorBridgeFactory: IosEpubNavigatorBridgeFactory,
    audioPlayerBridgeFactory: IosAudioPlayerBridgeFactory,
    pdfNavigatorBridgeFactory: IosPdfNavigatorBridgeFactory,
    publicationInspector: IosPublicationInspector,
) = module {
    single { createDefaultHttpClient() }
    single { AbsApiClient(get()) }
    single<AbsApi> { get<AbsApiClient>() }
    single<AbsLibraryApi> { get<AbsApiClient>() }
    single { KomgaLibraryApiClient(get()) }
    single<KomgaLibraryApi> { get<KomgaLibraryApiClient>() }
    single<KomgaCbzApi> { get<KomgaLibraryApiClient>() }

    single<DispatcherProvider> { IosDispatcherProvider }
    single<SourceRepository> { IosSourceRepositoryImpl(get(), get(), get()) }
    single<LibraryObserver> { IosLibraryObserverImpl(get(), get(), get(), get(), get()) }
    single<LibraryRefresher> { IosLibraryRefresherImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<LastOpenedLibraryStore> { IosLastOpenedLibraryStoreImpl() }
    single<LibraryVisibilityPreferencesStore> { IosLibraryVisibilityPreferencesStoreImpl() }
    single { RefreshLibraries(get()) }
    single { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    single { DrawerViewModel(get(), get(), get(), get()) }
    single {
        RiffleViewModel(
            libraryObserver = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            toReadRepository = get(),
            annotationsLibraryRepository = get(),
            connectivityObserver = get(),
            offlineAvailability = get(),
        )
    }

    // ---- Source onboarding (shared Compose screens from :feature:source-ui) -------------------
    // iOS renders the same SourceTypePicker / AddSource / SelectLibraries screens as Android.
    single<WebSourceRegistry> { WebSourceRegistry(WebSourceDescriptors.all) }
    single { SingletonWebSourceInstaller(get(), get(), get(), get()) }
    single { SourceTypePickerViewModel(sourceRepository = get(), developerOptions = get()) }
    single { SourceSetupViewModel() }
    single<SourceUiStrings> { ComposeResourceSourceUiStrings }
    // core:sources' AbsSourceAdapter and KomgaSourceAdapter are both commonMain.
    single { StorytellerApiClient(get()) }
    single<StorytellerApi> { get<StorytellerApiClient>() }
    single { AbsSourceAdapter(get(), get(), get()) }
    single { KomgaSourceAdapter(get()) }
    single<Map<SourceType, SourceAdapter>> {
        mapOf(
            SourceType.ABS to get<AbsSourceAdapter>(),
            SourceType.KOMGA to get<KomgaSourceAdapter>(),
        )
    }
    // The WebDAV annotation-sync sidecar is Android-only (its target factory lives in
    // core/sources' jvmMain). No iOS surface navigates to the WebDAV form; this binding exists
    // so the shared AddSourceViewModel graph resolves, and reports the unparseable-URL outcome
    // if it is ever reached.
    single<WebdavConnectionTester> { WebdavConnectionTester { WebdavTestOutcome.UnparseableUrl } }
    single<AnnotationSweepEnqueuer> { AnnotationSweepEnqueuer { } }
    single<ProgressSyncTrigger> { ProgressSyncTrigger { } }
    single { DevSourceDefaults.Empty }
    single<Flow<Unit>>(named(AddSourceViewModel.WEBDAV_BANNER_TICKER)) {
        flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        }
    }
    factory { params ->
        AddSourceViewModel(
            repository = get(),
            authenticators = get(),
            webdavConfigStore = get(),
            webdavConnectionTester = get(),
            webdavStatusStore = get(),
            sweepEnqueuer = get(),
            progressSyncTrigger = get(),
            storytellerSyncer = get(),
            readaloudMatcher = get(),
            tokenStorage = get(),
            clock = get(),
            annotationDao = get(),
            bannerTicker = get(named(AddSourceViewModel.WEBDAV_BANNER_TICKER)),
            strings = get(),
            devDefaults = get(),
            savedStateHandle = SavedStateHandle(mapOf("type" to params.get<String>())),
        )
    }
    factory { SelectLibrariesViewModel(repository = get(), strings = get()) }

    // EPUB reader
    single<IosEpubNavigatorBridgeFactory> { navigatorBridgeFactory }
    single { IosEpubDownloader(get(), get(), get(), get()) }
    single<IosPublicationInspector> { publicationInspector }
    single { TocRepositoryImpl(get(), get()) }
    single<TocRepository> { get<TocRepositoryImpl>() }
    single { PublicationMetricsRepositoryImpl(get(), get()) }
    single<PublicationMetricsRepository> { get<PublicationMetricsRepositoryImpl>() }
    single<EpubTocExtractor> { IosEpubTocExtractor(get(), get(), get(), get()) }

    // PDF reader
    single<IosPdfNavigatorBridgeFactory> { pdfNavigatorBridgeFactory }
    single { IosPdfDownloader(get(), get(), get()) }

    // CBZ reader
    single { IosCbzDownloader(get(), get(), get()) }
    single<CbzRepository> { IosCbzRepository(get(), get(), get(), get(), get()) }
    single<ReadingSessionRepository> {
        ReadingSessionRepositoryImpl(
            catalogRegistry = get(),
            sourceRepository = get(),
            positionStore = get(),
            audiobookPositionStore = get(),
            readaloudResumeStore = get(),
            libraryItemDao = get(),
            clock = get(),
        )
    }
    single { UpdateReadingProgress(get()) }
    single<PanelViewPreferencesStore> { IosPanelViewPreferencesStoreImpl() }
    single<AppearanceCoordinator> {
        AppearanceCoordinatorImpl(
            appThemeStore = get(),
            formattingPreferencesStore = get(),
            timeProvider = get(),
            scope = get<ApplicationScope>().coroutineScope,
        )
    }
    single<PanelReportRepository> {
        val developerOptionsRepository = get<DeveloperOptionsRepository>()
        val httpClient = get<HttpClient>()
        object : PanelReportRepository {
            override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> {
                val pat = developerOptionsRepository.getGithubPat()
                    ?: return Result.failure(IllegalStateException("No GitHub PAT configured"))
                return GitHubPanelReportRepository(pat = pat, client = httpClient).submit(report, maskPng)
            }
        }
    }
    single { VolumeNavigationController() }
    single { VolumeKeyDispatcher(get(), get()) }
    single { ReaderStateHolder() }
    factory { params ->
        CbzReaderViewModel(
            itemId = params.get(0),
            sourceId = params.values.getOrNull(1) as? String,
            libraryObserver = get(),
            cbzRepository = get(),
            readingSessionRepository = get(),
            updateReadingProgressUseCase = get(),
            wakeLockPreferencesStore = get(),
            volumeNavigationController = get(),
            volumeKeyDispatcher = get(),
            readerStateHolder = get(),
            panelEngine = get(),
            panelMaskService = get(),
            panelViewPreferencesStore = get(),
            comicFormattingPreferencesStore = get(),
            bookComicFormattingPreferencesStore = get(),
            developerOptionsRepository = get(),
            appearanceCoordinator = get(),
            colorPageDecoder = get(),
            dispatchers = get(),
            panelReportRepository = get(),
            applicationScope = get(),
        )
    }

    // Audiobook player
    single<AbsPlaybackApi> { get<AbsApiClient>() }
    single<AbsSessionApi> { get<AbsApiClient>() }
    single<IosAudioPlayerBridgeFactory> { audioPlayerBridgeFactory }
    // iOS registers no ABS Catalog (AbsCatalog is JVM-only), so the catalog-backed
    // AudiobookRepositoryImpl cannot open ABS sessions; IosAbsAudiobookRepository opens them
    // through AbsPlaybackApi directly and delegates every other source (regression fix for #1054).
    single<AudiobookRepository> {
        IosAbsAudiobookRepository(
            sourceRepository = get(),
            tokenStorage = get(),
            playbackApi = get(),
            sessionApi = get(),
            deviceIdStore = get(),
            delegate = AudiobookRepositoryImpl(get(), get()),
        )
    }
    single<BundleAudiobookSource> { IosNoOpBundleAudiobookSource }
    single<ContentCacheAccessStore> { IosContentCacheAccessStoreImpl(get()) }
    single<com.riffle.core.domain.AudioIdentityResolver> {
        AudioIdentityResolverImpl(get<ReadaloudLinkDao>(), get<LibraryItemDao>())
    }
    single<com.riffle.core.domain.AudioPlaybackPreferencesStore> { AudioPlaybackPreferencesStoreImpl(get<AudioPlaybackPreferencesDao>()) }
    single { NowPlayingStore() }
    single { AudiobookHandoffState() }
    single { OpenReconcileTargets() }
    single { ProgressFlushScope(applicationScope = get()) }
    // SyncPositionStore<Double>/<String> are bound in iosDataModule (core:data), backed by the
    // real ReadingPositionStoreImpl/AudiobookPositionStoreImpl (issue #1065 server-sync wiring).
    single<ReadaloudHandoff> { IosNoOpReadaloudHandoff }
    single { FollowLoopOrchestrator(clock = get(), progressFlushScope = get()) }
    single { AudiobookResumeResolver(positionStore = get(), clock = get()) }
    single {
        AudiobookReconciliationCoordinator(
            readerSyncFactory = IosNoOpReaderSyncFactory,
            openReconcileTargets = get(),
            audioSyncStore = get(),
            readingSyncStore = get(),
            readaloudResumeStore = get(),
        )
    }
    factory { params ->
        val bridge = get<IosAudioPlayerBridgeFactory>().create()
        AudiobookPlayerViewModel(
            navItemId = params.get(0),
            navSourceId = params.get(1),
            navPlaylistId = null,
            navPlaylistLibraryId = null,
            navStartAtSec = -1f,
            audiobookRepository = get(),
            audiobookDownloadRepository = get(),
            audiobookCacheRepository = get(),
            bundleAudiobookSource = get(),
            libraryObserver = get(),
            updateReadingProgressUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            controller = IosAudioPlayerController(bridge),
            readaloudHandoff = get(),
            audioPlaybackPreferencesStore = get(),
            listeningPreferencesStore = get(),
            audioIdentityResolver = get(),
            readaloudLinkRepository = get(),
            readaloudAudioRepository = get(),
            nowPlayingStore = get(),
            audiobookPositionStore = get(),
            openReconcileTargets = get(),
            progressFlushScope = get(),
            bookmarkStore = get(),
            connectivityObserver = get(),
            audiobookHandoffState = get(),
            followLoopOrchestrator = get(),
            resumeResolver = get(),
            reconciliationCoordinator = get(),
            clock = get(),
            logger = get(),
            playlistsRepository = get(),
            contentCacheAccessStore = get(),
            progressSweep = ProgressSweepRunner.NOOP,
        )
    }

    single<PlaylistsRepository> { IosPlaylistsRepositoryImpl(get(), get(), get(), get()) }
    single<ToReadRepository> { IosToReadRepositoryImpl(get(), get(), get(), get()) }
    single<LibraryItemOfflineAvailability> { IosLibraryItemOfflineAvailabilityImpl(get()) }
    // Readaloud matching pipeline, same implementations Android binds in CoreDataKoinModules:
    // the syncer pulls Storyteller catalogues into library_items, the matching service reconciles
    // them against ABS books into confirmed links / pending candidates.
    single<StorytellerReadaloudCacheSyncer> {
        StorytellerReadaloudSyncer(
            sourceRepository = get(),
            tokenStorage = get(),
            storytellerApi = get<StorytellerApiClient>(),
            libraryItemDao = get(),
            clock = get<Clock>()::nowMs,
        )
    }
    single<ReadaloudLinkReconciler> {
        ReadaloudMatchingService(
            libraryItemDao = get(),
            readaloudLinkDao = get(),
            readaloudCandidateDao = get(),
            readaloudDismissalDao = get(),
            clock = get<Clock>(),
            logger = get(),
        )
    }
    // Same DefaultApplicationScope Android binds: withSurvivable must run the block ON this
    // scope (async/await), not inline in the caller, or terminal writes die with the ViewModel.
    single<ApplicationScope> { DefaultApplicationScope(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single { RefreshLibraryItems(get(), get(), get(), get()) }
    single { RefreshCollections(get()) }
    single { RefreshSeries(get()) }
    single<DownloadsRepository> { IosDownloadsRepositoryImpl(get()) }
    single<ContentCacheSettingsStore> { IosContentCacheSettingsStoreImpl() }
    single<ReadaloudSidecarDownloads> { IosNoOpReadaloudSidecarDownloads }
    single {
        DownloadsViewModel(
            downloadsRepository = get(),
            libraryObserver = get(),
            sourceRepository = get(),
            readaloudLinkRepository = get(),
            sidecarStore = get(),
            contentCacheSettingsStore = get(),
        )
    }
    single<CrashReportRepository> { IosNoOpCrashReportRepository }
    single<AppUpdateRepository> { IosNoOpAppUpdateRepository }
    single<AppUpdatePreferencesStore> { IosAppUpdatePreferencesStoreImpl() }
    single { ReadaloudReviewRepositoryImpl(get(), get(), get(), get(), get(), get<Clock>()) }
    single<ReadaloudReviewRepository> { get<ReadaloudReviewRepositoryImpl>() }
    single<ReadaloudReviewMutator> { get<ReadaloudReviewRepositoryImpl>() }
    single { ReadaloudReviewActions(mutator = get(), linkRepository = get(), audioIdentityResolver = get(), audioPlaybackPreferencesStore = get()) }
    single<EncryptedKeyValueStore> { IosEncryptedKeyValueStore() }
    single<AnnotationSyncConfigStore> { AnnotationSyncConfigStoreImpl(get()) }
    single<LocalFilesScannerInterface> {
        val scanner = get<IosLocalFilesScanner>()
        object : LocalFilesScannerInterface {
            override suspend fun scan(sourceId: String) { scanner.scan(sourceId) }
        }
    }
    single<LocalFilesFolderRepositoryInterface> {
        val folderRepo = get<IosLocalFilesFolderRepository>()
        object : LocalFilesFolderRepositoryInterface {
            override suspend fun removeFolder(sourceId: String, treeUri: String) =
                folderRepo.removeFolder(sourceId, treeUri)
        }
    }
    single { com.riffle.core.sync.AnnotationSyncStatusStore() }
    single {
        val bundle = NSBundle.mainBundle
        val versionName = bundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "0.0.0"
        val buildNumber = (bundle.infoDictionary?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
        SettingsViewModel(
            appVersion = AppVersion(name = versionName, code = buildNumber),
            crashReportRepository = get(),
            formattingPreferencesStore = get(),
            sourceRepository = get(),
            libraryObserver = get(),
            visibilityStore = get(),
            orderStore = get(),
            wakeLockPreferencesStore = get(),
            volumeKeyPreferencesStore = get(),
            listeningPreferencesStore = get(),
            appThemeStore = get(),
            readaloudReviewRepository = get(),
            connectivityObserver = get(),
            appUpdateRepository = get(),
            appUpdatePreferencesStore = get(),
            readaloudPreferencesStore = get(),
            localFilesFolderDao = get(),
            localFilesFolderRepository = get(),
            localFilesScanner = get(),
            localFilesFolderHealthChecker = IosNoOpLocalFilesFolderHealthChecker,
            comicFormattingPreferencesStore = get(),
            developerOptionsRepository = get(),
            annotationSyncConfigStore = get(),
            annotationSyncStatusStore = get(),
            annotationDao = get(),
        )
    }
    single<Clock> { IosSystemClock }
    single<TimeProvider> { SystemTimeProvider }
    single<AnnotationStore> { AnnotationStoreImpl(dao = get(), deviceIdStore = get(), clock = get()) }
    single<AudiobookBookmarkStore> { AudiobookBookmarkStoreImpl(get<AudiobookBookmarkDao>()) }
    single<ReadaloudLinkRepository> { ReadaloudLinkRepositoryImpl(get<ReadaloudLinkDao>()) }
    single<AnnotationsLibraryRepository> { AnnotationsLibraryRepositoryImpl(annotationDao = get(), libraryItemDao = get(), sourceRepository = get()) }

    // LibraryItemDetailViewModel dependencies — real implementations on iOS
    single<EpubRepository> {
        IosEpubRepositoryImpl(positionStore = get(), fileStore = get(), sourceRepository = get(), tokenStorage = get(), httpClient = get())
    }
    single<EbookCfiTranslatorFactory> { IosEbookCfiTranslatorFactory(get()) }
    single { IosPdfRepositoryImpl(get(), get(), get(), get(), get()) }
    single<PdfRepository> { get<IosPdfRepositoryImpl>() }
    single<ReadaloudAudioRepository> { IosNoOpReadaloudAudioRepository() }
    // Offline audiobooks (ADR 0035), mirroring CoreDataKoinModules' coreDataStreamingAudioModule:
    // one shared track downloader feeds both the explicit user download and the background cache.
    single { IosAudiobookTrackDownloader(get(), get()) }
    single<AudiobookDownloadRepository> {
        IosAudiobookDownloadRepositoryImpl(
            audiobookRepository = get(),
            trackDownloader = get(),
            fileStore = get(),
            dispatchers = get(),
            localAvailabilityEvents = get(),
        )
    }
    single<AudiobookCacheRepository> {
        IosAudiobookCacheRepositoryImpl(
            trackDownloader = get(),
            fileStore = get(),
            dispatchers = get(),
            localAvailabilityEvents = get(),
        )
    }
    single<LocalAvailabilityEvents> { LocalAvailabilityEventsImpl() }
    single<CrossEpubIndexBuildTrigger> { IosNoOpCrossEpubIndexBuildTrigger }
    single<Map<SourceType, CatalogFactory>>(named("catalogFactoriesBySourceType")) {
        mapOf(
            SourceType.KOMGA to KomgaCatalogFactory(
                httpClient = get(),
                tokenStorage = get(),
                userAgent = "Riffle/dev (iOS) komga-source",
            ),
        )
    }
    single<CatalogRegistry> {
        DefaultCatalogRegistry(get(named("catalogFactoriesBySourceType")), get())
    }
    single<ReadaloudSidecarPrefetcher> { IosNoOpReadaloudSidecarPrefetcher }
    single<RecordItemOpened> { RecordItemOpened(get(), get()) }
    single<MarkReadAcrossDimensions> { MarkReadAcrossDimensions(get(), get(), get(), get()) }
    single<AudiobookChapterCacheRepository> { AudiobookChapterCacheRepositoryImpl(get(), get(), get()) }
    single { FetchAudiobookChaptersUseCase(get<AudiobookChapterCacheRepository>()) }
    single<ReadaloudOfflineDownloader> { IosNoOpReadaloudOfflineDownloader }
    single<DownloadManager> { IosDownloadManagerImpl(get()) }
    single<BookImportManager> { IosNoOpBookImportManager() }
    single<PdfPageCountExtractor> {
        val pdfRepository = get<IosPdfRepositoryImpl>()
        IosPdfPageCountExtractor({ sourceId, itemId -> pdfRepository.localPath(sourceId, itemId) }, get())
    }
    single<LocalFileMetadataOverrideSaver> {
        val uc = SaveLocalFileMetadataOverrideUseCase(get())
        object : LocalFileMetadataOverrideSaver {
            override suspend fun invoke(
                sourceId: String, sourceItemId: String, title: String?, author: String?,
                seriesName: String?, seriesIndex: Double?, coverUrl: String?,
            ) = uc(sourceId, sourceItemId, title, author, seriesName, seriesIndex, coverUrl)
        }
    }
    single<CoverImageCopier> { IosCoverImageCopier(get()) }
    single<WebSourceLibraryItemUpserter> { IosWebSourceLibraryItemUpserterImpl(get()) }

    // ViewModel factories — keyed by libraryId (+ sectionType for section screen)
    factory { params ->
        LibraryItemsViewModel(
            libraryId = params.get(),
            libraryObserver = get(),
            refreshLibraryItemsUseCase = get(),
            refreshSeriesUseCase = get(),
            refreshCollectionsUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            offlineAvailability = get(),
            connectivityObserver = get(),
            toReadRepository = get(),
            playlistsRepository = get(),
            readaloudLinkRepository = get(),
            coverGridDensityStore = get(),
            libraryFilterPreferencesStore = get(),
            annotationStore = get(),
            audiobookBookmarkStore = get(),
            annotationsLibraryRepository = get(),
            dispatchers = get(),
        )
    }
    factory { params ->
        LibrarySectionViewModel(
            libraryId = params.get(),
            sectionType = params.get(),
            libraryObserver = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }
    factory { params ->
        AnnotationsListViewModel(
            libraryId = params.get(),
            sourceRepository = get(),
            repo = get(),
            tokenStorage = get(),
        )
    }
    factory { params ->
        LibraryItemDetailViewModel(
            itemId = params.get(),
            sourceId = params.get(),
            libraryObserver = get(),
            recordItemOpened = get(),
            updateReadingProgressUseCase = get(),
            markReadAcrossDimensions = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            epubRepository = get(),
            ebookCfiTranslatorFactory = get(),
            audiobookPositionStore = get(),
            pdfRepository = get(),
            cbzRepository = get(),
            toReadRepository = get(),
            playlistsRepository = get(),
            readaloudLinkRepository = get(),
            readaloudAudioRepository = get(),
            audiobookDownloadRepository = get(),
            audiobookCacheRepository = get(),
            localAvailabilityEvents = get(),
            readaloudOfflineDownloader = get(),
            connectivityObserver = get(),
            downloadManager = get(),
            bookImportManager = get(),
            crossEpubIndexBuildTrigger = get(),
            sidecarPrefetcher = get(),
            epubTocExtractor = get(),
            pdfPageCountExtractor = get(),
            fetchAudiobookChaptersUseCase = get(),
            catalogRegistry = get(),
            libraryRefresher = get(),
            saveLocalFileMetadataOverride = get(),
            copyCoverImage = get(),
            readingSpeedStore = get(),
            webSourceLibraryItemUpserter = get(),
        )
    }
    factory { params ->
        SeriesDetailViewModel(
            seriesId = params.get(),
            libraryId = params.get(),
            libraryObserver = get(),
            refreshSeriesUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            offlineAvailability = get(),
            connectivityObserver = get(),
        )
    }
    factory { params ->
        CollectionDetailViewModel(
            collectionId = params.get(),
            libraryId = params.get(),
            libraryObserver = get(),
            refreshCollectionsUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            offlineAvailability = get(),
            connectivityObserver = get(),
            dispatchers = get(),
        )
    }
}

fun startKoin(
    navigatorBridgeFactory: IosEpubNavigatorBridgeFactory,
    audioPlayerBridgeFactory: IosAudioPlayerBridgeFactory,
    pdfNavigatorBridgeFactory: IosPdfNavigatorBridgeFactory,
    publicationInspector: IosPublicationInspector,
) {
    koinStartKoin {
        modules(
            iosLoggingModule,
            iosDataModule,
            iosDatabaseModule,
            iosLibraryModule(navigatorBridgeFactory, audioPlayerBridgeFactory, pdfNavigatorBridgeFactory, publicationInspector),
        )
    }
}
