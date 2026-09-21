package com.riffle.shared

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.DefaultCatalogRegistry
import com.riffle.core.catalog.abs.AbsCommonCatalogFactory
import com.riffle.core.catalog.chitanka.ChitankaCatalogFactory
import com.riffle.core.catalog.gutenberg.GutenbergCatalogFactory
import com.riffle.core.catalog.komga.KomgaCatalogFactory
import com.riffle.core.catalog.radioes.RadioEsCatalogFactory
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
import com.riffle.core.data.CrossEpubIndexStoreImpl
import com.riffle.core.data.IosAppUpdatePreferencesStoreImpl
import com.riffle.core.data.IosAppUpdateRepositoryImpl
import com.riffle.core.data.IosAudiobookCacheRepositoryImpl
import com.riffle.core.data.IosAudiobookDownloadRepositoryImpl
import com.riffle.core.data.IosAudiobookTrackDownloader
import com.riffle.core.data.IosContentCacheAccessStoreImpl
import com.riffle.core.data.IosContentCacheArtifactScannerImpl
import com.riffle.core.data.IosCrashReportRecorder
import com.riffle.core.data.IosCrashReportRepositoryImpl
import com.riffle.core.data.IosCrossEpubIndexBuilderService
import com.riffle.core.data.IosDownloadsRepositoryImpl
import com.riffle.core.data.IosEncryptedKeyValueStore
import com.riffle.core.data.IosEpubAnalyzer
import com.riffle.core.data.IosLastOpenedLibraryStoreImpl
import com.riffle.core.data.IosLibraryItemOfflineAvailabilityImpl
import com.riffle.core.data.IosLibraryObserverImpl
import com.riffle.core.data.IosLibraryRefresherImpl
import com.riffle.core.data.IosLibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.IosLocalEpubLocator
import com.riffle.core.data.IosPanelViewPreferencesStoreImpl
import com.riffle.core.data.IosPlaylistsRepositoryImpl
import com.riffle.core.data.IosReadaloudAudioRepositoryImpl
import com.riffle.core.data.IosReadaloudSidecarStore
import com.riffle.core.data.IosSourceRepositoryImpl
import com.riffle.core.data.IosToReadRepositoryImpl
import com.riffle.core.data.LocalAvailabilityEventsImpl
import com.riffle.core.data.OfflineAvailabilitySnapshot
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.PublicationMetricsRepositoryImpl
import com.riffle.core.data.ReadaloudLinkRepositoryImpl
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadaloudReviewRepositoryImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.StorytellerBundleAudiobookSource
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.TocRepositoryImpl
import com.riffle.core.data.comic.panel.GitHubPanelReportRepository
import com.riffle.core.data.di.RIFFLE_DATABASE_FILE
import com.riffle.core.data.di.iosDataModule
import com.riffle.core.data.di.iosDatabaseModule
import com.riffle.core.data.localfiles.IosLocalFilesFolderHealthChecker
import com.riffle.core.data.localfiles.IosLocalFilesFolderRepository
import com.riffle.core.data.localfiles.IosLocalFilesScanner
import com.riffle.core.data.localfiles.SaveLocalFileMetadataOverrideUseCase
import com.riffle.core.data.readaloudLinksByAbsItemKey
import com.riffle.core.data.websource.RemoteItemFreshness
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.data.websource.WebSourceItemGate
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
import com.riffle.core.domain.ContentCacheArtifactScanner
import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.DefaultApplicationScope
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.IosReadaloudEpubTextOps
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
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
import com.riffle.core.domain.localfiles.LocalFilesFolderHealthCheckerInterface
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
import com.riffle.core.network.AbsServerInfoApi
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
import com.riffle.core.sync.ForegroundSyncDriver
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.core.sync.ProgressSweep
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.BookImportManagerImpl
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
import com.riffle.feature.library.PlaylistDetailViewModel
import com.riffle.feature.library.ReadaloudOfflineDownloader
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.SeriesDetailViewModel
import com.riffle.feature.library.WebSourceLibraryItemUpserter
import com.riffle.feature.library.urlFormEncode
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
import com.riffle.feature.reader.ReaderSyncFactory
import com.riffle.feature.reader.ReaderSyncFactoryInterface
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
import com.riffle.feature.source.ui.websource.ChitankaBrowseViewModel
import com.riffle.feature.source.ui.websource.GutenbergBrowseViewModel
import com.riffle.feature.source.ui.websource.RadioEsBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedBrowseViewModel
import com.riffle.shared.audiobook.IosAbsAudiobookRepository
import com.riffle.shared.audiobook.IosAudioPlayerBridgeFactory
import com.riffle.shared.audiobook.IosAudioPlayerController
import com.riffle.shared.library.IosContentCacheSettingsStoreImpl
import com.riffle.shared.library.IosCoverImageCopier
import com.riffle.shared.library.IosDownloadManagerImpl
import com.riffle.shared.library.IosEpubRepositoryImpl
import com.riffle.shared.library.IosPdfPageCountExtractor
import com.riffle.shared.library.IosPdfRepositoryImpl
import com.riffle.shared.library.IosReadaloudHandoff
import com.riffle.shared.library.IosReadaloudOfflineDownloader
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
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    // Needed by AbsCommonCatalogFactory for Catalog.connectivityCheck.
    single<AbsServerInfoApi> { get<AbsApiClient>() }
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
    // The WebDAV annotation-sync sidecar has no iOS engine and no iOS form (#1071 §17, #1072).
    //
    // Unreachable, not merely unused: the iOS Add-Source picker offers ABS, Komga and Local Files
    // only (`iosSupportedSourceTypes()`, pinned by IosSupportedSourceTypesTest), and
    // SourceOnboardingHost always builds `AddSourceBackend.Credentialed(type, AUDIOBOOKSHELF)`,
    // so no code path can construct the WebDAV backend whose form calls this tester. The binding
    // exists solely because the shared AddSourceViewModel takes it as a constructor argument.
    //
    // Making it real is a port, not a wiring change: WebDavAnnotationSyncTarget lives in
    // core/sources' jvmMain and its `testConnection` needs java.util.Base64 for the Basic auth
    // header plus javax.net.ssl / java.io catch clauses to classify transport errors. Porting only
    // the tester would still leave nothing to test a connection *for*, because every consumer of a
    // WebDAV target (AnnotationSyncTargetHolder, WebDavProgressRemote, CatalogRemoteProgressIndex)
    // is androidMain/jvmMain too, and the PROPFIND paths additionally need a multiplatform XML
    // parser and an RFC-1123 date parser. Tracked in #1072.
    single<WebdavConnectionTester> { WebdavConnectionTester { WebdavTestOutcome.UnparseableUrl } }
    // Annotation sync has no iOS engine at all: AnnotationSyncController/AnnotationSweep are
    // androidMain and the only sync target (WebDAV) is jvmMain. This is a missing surface (#1072),
    // not dead wiring — there is nothing for the enqueuer to enqueue, so it stays a no-op until
    // the engine is ported. The progress half is real (see ProgressSyncTrigger below).
    single<AnnotationSweepEnqueuer> { AnnotationSweepEnqueuer { } }
    // Runs the real ProgressSweep the moment a source's sync config is saved, so anything that
    // went dirty while the source was misconfigured is pushed without waiting for the next
    // launch. Android's equivalent enqueues ProgressSyncScheduler.sweepNow (#1071 §14).
    single<ProgressSyncTrigger> {
        val scope = get<ApplicationScope>()
        val sweep = get<ProgressSweep>()
        ProgressSyncTrigger {
            scope.launchSurvivable { runCatching { sweep.run() } }
        }
    }
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
    // Constructor dependency of the commonMain CbzReaderViewModel, which writes it from
    // IosCbzReaderScreen's DisposableEffect — so the binding cannot be deleted. On Android the
    // only *reader* is MainActivity.onKeyDown, which consults it to decide whether a hardware
    // volume press is a page turn. iOS has no hardware-key surface to read it from yet, so the
    // three flags are written and never consulted (#1071 §17; the volume-key reader is #1072).
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
    // Same StorytellerBundleAudiobookSource Android binds (ADR 0027): a downloaded readaloud
    // bundle doubles as the offline audiobook, resolved ABS item -> Storyteller book via the link.
    single<BundleAudiobookSource> {
        val readaloudLinkRepository = get<ReadaloudLinkRepository>()
        StorytellerBundleAudiobookSource(
            readaloudLinkRepository = readaloudLinkRepository,
            readaloudAudioRepository = get<ReadaloudBundleReader>(),
            audioAvailability = get<ReadaloudAudioRepository>(),
            linksByAbsItem = OfflineAvailabilitySnapshot(
                applicationScope = get(),
                source = readaloudLinkRepository.observeAll().map(::readaloudLinksByAbsItemKey),
            ),
        )
    }
    single<ContentCacheAccessStore> { IosContentCacheAccessStoreImpl(get()) }
    single<com.riffle.core.domain.AudioIdentityResolver> {
        AudioIdentityResolverImpl(get<ReadaloudLinkDao>(), get<LibraryItemDao>())
    }
    single<com.riffle.core.domain.AudioPlaybackPreferencesStore> { AudioPlaybackPreferencesStoreImpl(get<AudioPlaybackPreferencesDao>()) }
    // Constructor dependency of the commonMain AudiobookPlayerViewModel, which sets and clears it
    // around playback — so the binding cannot be deleted. Android's only reader is MainScreen's
    // openNowPlayingRequests collector, which turns a media-notification tap into a nav route;
    // iOS's DrawerViewModel has no equivalent and IosAudioPlayerBridgeImpl talks to
    // MPNowPlayingInfoCenter (the OS widget) rather than this store, so a lock-screen tap cannot
    // route back to the player (#1071 §17). The routing surface is #1072.
    single { NowPlayingStore() }
    single { AudiobookHandoffState() }
    single { OpenReconcileTargets() }
    // Live on iOS: FollowLoopOrchestrator.flush (per-tick audiobook position writes) and
    // AudiobookPlayerViewModel's speed-change and onCleared flushes all run through it.
    single { ProgressFlushScope(applicationScope = get()) }
    // SyncPositionStore<Double>/<String> are bound in iosDataModule (core:data), backed by the
    // real ReadingPositionStoreImpl/AudiobookPositionStoreImpl (issue #1065 server-sync wiring).
    single { IosReadaloudHandoff() }
    single<ReadaloudHandoff> { get<IosReadaloudHandoff>() }
    // Same ReaderSyncFactory Android binds (ADR 0023), now that it is commonMain: reader <->
    // audiobook position sync for a matched book, over iOS's EPUB locator/analyzer.
    // Live on iOS: AudiobookReconciliationCoordinator.attach calls createIfApplicable /
    // createAudiobookFollowIfApplicable, and AudiobookPlayerViewModel drives the coordinator on
    // prepare, handoff activation and onCleared — reached from IosAudiobookPlayerScreen.
    single<ReaderSyncFactoryInterface> {
        ReaderSyncFactory(
            linkRepository = get(),
            sourceRepository = get(),
            catalogRegistry = get(),
            indexStore = get(),
            libraryObserver = get(),
            epubLocator = IosLocalEpubLocator(get(), get()),
            epubAnalyzer = IosEpubAnalyzer,
            textOps = IosReadaloudEpubTextOps,
            crossEpubIndexBuildTrigger = get(),
            clock = get(),
            logger = get(),
        )
    }
    single { FollowLoopOrchestrator(clock = get(), progressFlushScope = get()) }
    single { AudiobookResumeResolver(positionStore = get(), clock = get()) }
    single {
        AudiobookReconciliationCoordinator(
            readerSyncFactory = get(),
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
            // The playlist the player was opened *from*, which is what the ViewModel uses to
            // look up the next item at end-of-book and auto-advance into it. Android fills these
            // from nav-route query args (audiobook_player/{sourceId}/{itemId}?playlistId=...&
            // libraryId=...); iOS has no route layer, so they ride on
            // LibraryNav.AudiobookPlayer, which PlaylistDetailScreen's "Play" fills in. Empty
            // for every other entry point — a library row, the Riffle hub, a series — and the
            // ViewModel already treats "" as absent.
            navPlaylistId = params.get<String>(2),
            navPlaylistLibraryId = params.get<String>(3),
            // Bookmark jumps happen inside the open player through the VM, not through
            // navigation, so iOS has no navigation-time start position (#1071 §17).
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
            progressSweep = ProgressSweepRunner { get<ProgressSweep>().run() },
        )
    }

    // Read on iOS by LibraryItemsViewModel.playlists, which LibraryItemsScreen renders as the
    // Playlists tab. The AudiobookPlayerViewModel injection stays dead until navPlaylistId can be
    // supplied (see the factory above and #1072).
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
    single<DownloadsRepository> { IosDownloadsRepositoryImpl(get(), get()) }
    single<ContentCacheSettingsStore> { IosContentCacheSettingsStoreImpl() }
    single<ContentCacheArtifactScanner> { IosContentCacheArtifactScannerImpl(get()) }
    // The cleaner is shared (core:domain commonMain) — iOS has no BGTaskScheduler, so it runs
    // once per foreground pass from RiffleAppRoot instead of on a periodic WorkManager job.
    single {
        ContentCacheCleaner(
            settingsStore = get(),
            accessStore = get(),
            artifactScanner = get(),
            clock = get(),
            dispatchers = get(),
            onRemoved = { key -> get<LocalAvailabilityEvents>().notifyChanged(key.sourceId, key.itemId) },
        )
    }

    // #1071 §14 — iOS's replacement for Android's WorkManager sync jobs. Android schedules
    // ProgressSyncScheduler/AnnotationSyncScheduler sweepNow + ensurePeriodic in
    // RiffleApplication.onCreate; iOS has no background execution (Info.plist declares only
    // `UIBackgroundModes: audio`, and there is no BGTaskScheduler registration anywhere), so the
    // driver sweeps at app start, on every UIApplicationDidBecomeActive, and on the validated
    // offline→online edge. RiffleAppRoot calls `drive(...)`.
    //
    // `runAnnotationSweep` stays at its no-op default: AnnotationSyncController/AnnotationSweep
    // are androidMain and the only sync target (WebDAV) is jvmMain, so there is no iOS annotation
    // engine to sweep — a missing surface (#1072), not a wiring gap.
    single { IosAppActiveEvents() }
    single<Flow<Unit>>(named(ForegroundSyncDriver.APP_BECAME_ACTIVE)) { get<IosAppActiveEvents>().becameActive }
    single {
        val sweep = get<ProgressSweep>()
        ForegroundSyncDriver(
            runProgressSweep = { sweep.run() },
            nowMs = get<Clock>()::nowMs,
        )
    }

    // One IosReadaloudSidecarStore serves both roles, as ReadaloudSidecarStore does on Android.
    single { IosReadaloudSidecarStore(get(), get(), get(), get(), get()) }
    single<ReadaloudSidecarDownloads> { get<IosReadaloudSidecarStore>() }
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
    single { IosCrashReportRepositoryImpl(get()) }
    single<CrashReportRepository> { get<IosCrashReportRepositoryImpl>() }
    single<AppUpdateRepository> { IosAppUpdateRepositoryImpl(get()) }
    single<AppUpdatePreferencesStore> { IosAppUpdatePreferencesStoreImpl() }
    single { ReadaloudReviewRepositoryImpl(get(), get(), get(), get(), get(), get<Clock>()) }
    single<ReadaloudReviewRepository> { get<ReadaloudReviewRepositoryImpl>() }
    single<ReadaloudReviewMutator> { get<ReadaloudReviewRepositoryImpl>() }
    single { ReadaloudReviewActions(mutator = get(), linkRepository = get(), audioIdentityResolver = get(), audioPlaybackPreferencesStore = get()) }
    single<EncryptedKeyValueStore> { IosEncryptedKeyValueStore() }
    single<AnnotationSyncConfigStore> { AnnotationSyncConfigStoreImpl(get()) }
    single<LocalFilesFolderHealthCheckerInterface> { IosLocalFilesFolderHealthChecker() }
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
            localFilesFolderHealthChecker = get(),
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
    single { IosReadaloudAudioRepositoryImpl(get(), get(), get(), get(), get()) }
    single<ReadaloudAudioRepository> { get<IosReadaloudAudioRepositoryImpl>() }
    single<ReadaloudBundleReader> { get<IosReadaloudAudioRepositoryImpl>() }
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
    single<CrossEpubIndexStore> { CrossEpubIndexStoreImpl(get()) }
    single<CrossEpubIndexBuildTrigger> {
        IosCrossEpubIndexBuilderService(get(), get(), get(), get<Clock>()::nowMs, get())
    }
    // Every progress push runs through CatalogRegistry: ReadingSessionRepositoryImpl.runSyncCycle
    // and AudiobookRepositoryImpl.saveProgress both bail out when `forSource`/`forSourceId` returns
    // null. With Komga as the only entry, reading or listening on iPhone never moved the book on
    // the user's Audiobookshelf server, in either medium, while resume *from* the server kept
    // working — so sync looked healthy while being one-directional (#1071 §P0.1).
    single<Map<SourceType, CatalogFactory>>(named("catalogFactoriesBySourceType")) {
        mapOf(
            // AbsCatalog itself is jvmMain-only (import/upload needs java.io.File, byte streaming
            // needs core:network's AbsFileDownloadApi). AbsCommonCatalogFactory builds the
            // commonMain half — browse + the full ebook/audiobook progress peer — which is the
            // same implementation AbsCatalog delegates those members to on Android.
            SourceType.ABS to AbsCommonCatalogFactory(
                libraryApi = get(),
                sessionApi = get(),
                serverInfoApi = get(),
                tokenStorage = get(),
                deviceIdStore = get(),
                clock = get(),
            ),
            SourceType.KOMGA to KomgaCatalogFactory(
                httpClient = get(),
                tokenStorage = get(),
                userAgent = "Riffle/dev (iOS) komga-source",
            ),
            // The three zero-config unbounded catalogues. Without a factory here
            // `CatalogRegistry.forSource` returns null for them and every browse call no-ops, so
            // installing one landed the user in a permanently empty library with no error
            // (#1071 §17). All three catalogues are `commonMain` — Chitanka's scraper moved off
            // jsoup onto ksoup for exactly this.
            SourceType.CHITANKA to ChitankaCatalogFactory(
                httpClient = get(),
                userAgent = "Riffle/dev (iOS) chitanka-source",
            ),
            SourceType.GUTENBERG to GutenbergCatalogFactory(
                sharedHttpClient = get(),
                userAgent = "Riffle/dev (iOS) gutenberg-source",
            ),
            SourceType.RADIO_ES to RadioEsCatalogFactory(
                httpClient = get(),
                userAgent = "Riffle/dev (iOS) radio-es-source",
            ),
        )
    }
    single<CatalogRegistry> {
        DefaultCatalogRegistry(get(named("catalogFactoriesBySourceType")), get())
    }
    single<ReadaloudSidecarPrefetcher> { get<IosReadaloudSidecarStore>() }
    single<RecordItemOpened> { RecordItemOpened(get(), get()) }
    single<MarkReadAcrossDimensions> { MarkReadAcrossDimensions(get(), get(), get(), get()) }
    single<AudiobookChapterCacheRepository> { AudiobookChapterCacheRepositoryImpl(get(), get(), get()) }
    single { FetchAudiobookChaptersUseCase(get<AudiobookChapterCacheRepository>()) }
    single<ReadaloudOfflineDownloader> { IosReadaloudOfflineDownloader(get(), get(), get()) }
    single<DownloadManager> { IosDownloadManagerImpl(get()) }
    single<BookImportManager> { BookImportManagerImpl(scope = get<ApplicationScope>().coroutineScope, logger = get()) }
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

    // ADR-0052 web-source item cache. Android binds the same pair in CoreDataKoinModules; iOS had
    // neither, because nothing on iOS could browse an unbounded catalogue to reach them.
    factory { RemoteItemFreshness(dao = get(), clock = get()) }
    factory {
        WebSourceItemGate(
            libraryObserver = get(),
            freshness = get(),
            upserter = get<com.riffle.core.data.websource.WebSourceLibraryItemUpserter>(),
            logger = get(),
        )
    }

    // Browse ViewModels for the unbounded catalogues. They live in `feature:source-ui`'s
    // commonMain, so these are the same classes Android's KoinViewModelModules constructs — the
    // `libraryId` param is the Catalog rootId, handed over through SavedStateHandle exactly as
    // Android's nav route arg does.
    factory { params ->
        ChitankaBrowseViewModel(
            savedStateHandle = browseSavedStateHandle(params.get()),
            sourceRepository = get(),
            catalogRegistry = get(),
            libraryItemUpserter = get<com.riffle.core.data.websource.WebSourceLibraryItemUpserter>(),
            webSourceItemGate = get(),
            coverGridDensityStore = get(),
            libraryFilterPreferencesStore = get(),
            libraryObserver = get(),
            connectivityObserver = get(),
        )
    }
    factory { params ->
        GutenbergBrowseViewModel(
            savedStateHandle = browseSavedStateHandle(params.get()),
            sourceRepository = get(),
            catalogRegistry = get(),
            libraryItemUpserter = get<com.riffle.core.data.websource.WebSourceLibraryItemUpserter>(),
            webSourceItemGate = get(),
            coverGridDensityStore = get(),
            libraryFilterPreferencesStore = get(),
            libraryObserver = get(),
            connectivityObserver = get(),
        )
    }
    factory { params ->
        RadioEsBrowseViewModel(
            savedStateHandle = browseSavedStateHandle(params.get()),
            sourceRepository = get(),
            catalogRegistry = get(),
            libraryItemUpserter = get<com.riffle.core.data.websource.WebSourceLibraryItemUpserter>(),
            webSourceItemGate = get(),
            coverGridDensityStore = get(),
            libraryFilterPreferencesStore = get(),
            libraryObserver = get(),
            connectivityObserver = get(),
        )
    }

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
    // (libraryId, playlistId, playlistName) — the three route args Android's
    // `playlist_detail/{libraryId}/{playlistId}/{playlistName}` carries, packed into the handle
    // the shared ViewModel reads them from.
    factory { params ->
        PlaylistDetailViewModel(
            savedStateHandle = playlistSavedStateHandle(
                libraryId = params.get(0),
                playlistId = params.get(1),
                playlistName = params.get(2),
            ),
            playlistsRepository = get(),
            libraryObserver = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }
}

/**
 * The [SavedStateHandle] an unbounded browse ViewModel reads its Catalog rootId from.
 *
 * `UnboundedBrowseViewModel` takes the rootId as the `libraryId` route arg because Android's nav
 * graph puts it there; iOS has no nav library, so the library the drawer selected is packed into
 * an equivalent handle here. Keyed on the same string on both platforms so a change to one host's
 * key cannot silently leave the other browsing the default root.
 */
private fun browseSavedStateHandle(libraryId: String): SavedStateHandle =
    SavedStateHandle(mapOf(UnboundedBrowseViewModel.ROUTE_ARG_LIBRARY_ID to libraryId))

/**
 * The [SavedStateHandle] [PlaylistDetailViewModel] reads its three route arguments from.
 *
 * Keyed on the constants the ViewModel owns, for the same reason as [browseSavedStateHandle]:
 * Android puts them in the nav route and iOS packs them here, and a change to one host's key
 * would otherwise leave the other loading an empty playlist with no failure anywhere.
 *
 * [playlistName] is form-encoded on the way in because the ViewModel `urlDecode()`s it —
 * Android's nav route arrives percent-encoded. Passing the display name raw would corrupt any
 * name containing `+` or `%`, so the encode/decode pair is kept symmetric rather than relying on
 * the decoder being a no-op for "ordinary" names.
 */
private fun playlistSavedStateHandle(
    libraryId: String,
    playlistId: String,
    playlistName: String,
): SavedStateHandle = SavedStateHandle(
    mapOf(
        PlaylistDetailViewModel.ROUTE_ARG_LIBRARY_ID to libraryId,
        PlaylistDetailViewModel.ROUTE_ARG_PLAYLIST_ID to playlistId,
        PlaylistDetailViewModel.ROUTE_ARG_PLAYLIST_NAME to playlistName.urlFormEncode(),
    ),
)

/**
 * The Swift entry point. Its parameter list is the ObjC-exported surface, so it deliberately
 * carries **no defaulted parameters**: Kotlin default arguments do not cross the Objective-C
 * boundary and Swift sees every one of them as required, which breaks `RiffleApp.swift` at build
 * time and only `xcodebuild` catches it. Anything optional belongs on [startKoinWithDatabase].
 */
fun startKoin(
    navigatorBridgeFactory: IosEpubNavigatorBridgeFactory,
    audioPlayerBridgeFactory: IosAudioPlayerBridgeFactory,
    pdfNavigatorBridgeFactory: IosPdfNavigatorBridgeFactory,
    publicationInspector: IosPublicationInspector,
) = startKoinWithDatabase(
    navigatorBridgeFactory = navigatorBridgeFactory,
    audioPlayerBridgeFactory = audioPlayerBridgeFactory,
    pdfNavigatorBridgeFactory = pdfNavigatorBridgeFactory,
    publicationInspector = publicationInspector,
    databaseFile = RIFFLE_DATABASE_FILE,
)

/**
 * Same graph, with the database file named explicitly. `IosKoinGraphTest` starts the real graph
 * once per case and needs each to have its own file, or the connections pile up and a later case
 * loses the race with `SQLITE_BUSY`.
 */
internal fun startKoinWithDatabase(
    navigatorBridgeFactory: IosEpubNavigatorBridgeFactory,
    audioPlayerBridgeFactory: IosAudioPlayerBridgeFactory,
    pdfNavigatorBridgeFactory: IosPdfNavigatorBridgeFactory,
    publicationInspector: IosPublicationInspector,
    databaseFile: String,
) {
    val app = koinStartKoin {
        modules(
            iosLoggingModule,
            iosDataModule,
            iosDatabaseModule(databaseFile),
            iosLibraryModule(navigatorBridgeFactory, audioPlayerBridgeFactory, pdfNavigatorBridgeFactory, publicationInspector),
        )
    }

    // Install the unhandled-exception hook. IosCrashReportRecorder was written with #1065 but
    // never invoked, so IosCrashReportRepositoryImpl listed a directory nothing ever wrote to and
    // Settings read "No crashes recorded" forever. Android installs its equivalent from
    // RiffleApplication; this is the iOS counterpart and belongs at the same point in startup.
    IosCrashReportRecorder.install(
        repository = app.koin.get<IosCrashReportRepositoryImpl>(),
        clock = app.koin.get<Clock>(),
    )
}
