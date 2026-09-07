@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.di

import com.riffle.app.feature.audio.AudiobookHttpItemRestorer
import com.riffle.app.feature.audio.BundleAudioSourceFactory
import com.riffle.app.feature.audio.BundleZipItemRestorer
import com.riffle.app.feature.audio.DefaultMediaSessionConnector
import com.riffle.app.feature.audio.FileAudioSourceFactory
import com.riffle.app.feature.audio.HttpAudioSourceFactory
import com.riffle.app.feature.audio.MediaItemRestorerRegistry
import com.riffle.app.feature.audio.MediaSessionConnector
import com.riffle.app.feature.audio.MediaSourceRegistry
import com.riffle.app.feature.audio.StreamingReadaloudItemRestorer
import com.riffle.app.feature.audiobook.AudiobookController
import com.riffle.app.feature.audiobook.AudiobookHandoffState
import com.riffle.feature.player.AudioPlayerInterface
import com.riffle.feature.player.ReadaloudHandoff
import com.riffle.app.feature.audiobook.AudiobookReconciliationCoordinator
import com.riffle.app.feature.audiobook.AudiobookResumeResolver
import com.riffle.app.feature.audiobook.FollowLoopOrchestrator
import com.riffle.app.feature.library.BookImportManager
import com.riffle.app.feature.library.DownloadManager
import com.riffle.app.feature.library.LibraryTabVisibilityObserver
import com.riffle.app.feature.reader.EbookCfiTranslatorFactoryImpl
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.ReaderStateHolder
import com.riffle.app.feature.reader.ReaderSyncFactory
import com.riffle.app.feature.reader.VolumeNavigationController
import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.app.feature.reader.cadence.CadenceController
import com.riffle.app.feature.reader.controllers.BookmarksController
import com.riffle.app.feature.reader.controllers.SearchController
import com.riffle.app.feature.reader.controllers.VolumeKeyDispatcher
import com.riffle.app.feature.reader.controllers.WakeLockController
import com.riffle.app.feature.reader.highlights.HighlightsPdfExporter
import com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory
import com.riffle.app.feature.reader.FiguresInRangeResolver
import com.riffle.app.feature.reader.NoopFiguresInRangeResolver
import com.riffle.app.feature.reader.highlights.NoopResourceFetcher
import com.riffle.app.feature.reader.highlights.ResourceFetcher
import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.source.localfiles.PdfiumPdfMetadataExtractor
import com.riffle.core.domain.PdfMetadataExtractor
import com.riffle.app.feature.reader.readaloud.ReadaloudController
import com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader
import com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloaderImpl
import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.app.feature.reader.session.AnnotationSession
import com.riffle.app.feature.reader.session.FormattingSession
import com.riffle.app.feature.reader.session.PositionOrchestrator
import com.riffle.app.feature.reader.session.ReadaloudSession
import com.riffle.app.feature.reader.session.ReaderSessionLifecycle
import com.riffle.feature.source.ui.AddSourceViewModel
import com.riffle.feature.source.ui.ComposeResourceSourceUiStrings
import com.riffle.feature.source.ui.DevSourceDefaults
import com.riffle.feature.source.ui.ProgressSyncTrigger
import com.riffle.feature.source.ui.SourceUiStrings
import com.riffle.app.sync.WebDavTargetConnectionTester
import com.riffle.feature.source.ui.WebdavConnectionTester
import com.riffle.app.feature.update.isDevVersionName
import com.riffle.app.playback.NowPlayingNavigator
import com.riffle.app.playback.NowPlayingStore
import com.riffle.app.sync.AnnotationSweepEnqueuerImpl
import com.riffle.app.sync.ProgressSyncScheduler
import com.riffle.app.update.AndroidApkInstaller
import com.riffle.core.common.Clock
import com.riffle.core.common.RandomProvider
import com.riffle.core.common.SystemClock
import com.riffle.core.common.SystemRandomProvider
import com.riffle.core.common.SystemTimeProvider
import com.riffle.core.common.TimeProvider
import com.riffle.core.data.AppearanceCoordinatorImpl
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ApkInstaller
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.appearance.AppearanceCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import com.riffle.app.feature.library.ExtractPdfPageCountUseCase
import com.riffle.app.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.app.feature.reader.ExtractEpubTocUseCase
import com.riffle.core.data.localfiles.CopyCoverImageUseCase
import com.riffle.core.data.localfiles.SaveLocalFileMetadataOverrideUseCase
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.CoverImageCopier
import com.riffle.feature.library.EpubDetails
import com.riffle.feature.library.EpubTocExtractor
import com.riffle.feature.library.LocalFileMetadataOverrideSaver
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.feature.library.WebSourceLibraryItemUpserter
import com.riffle.feature.library.FetchAudiobookChaptersUseCase as SharedFetchAudiobookChaptersUseCase
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.ReadaloudReviewActions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.domain.usecase.UpdateReadingProgress
import kotlinx.coroutines.flow.flow
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

val appKoinModule: Module = module {

    // ---- Core utilities -----------------------------------------------------------------------

    single<Clock> { SystemClock }
    single<RandomProvider> { SystemRandomProvider }
    single<TimeProvider> { SystemTimeProvider }
    single<DispatcherProvider> { DefaultDispatcherProvider }

    // ---- Coroutine scopes --------------------------------------------------------------------

    single<CoroutineScope>(named("downloadScope")) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    }
    single<CoroutineScope>(named("applicationCoroutineScope")) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    }
    single<ApplicationScope> { DefaultApplicationScope(get(named("applicationCoroutineScope"))) }

    // ---- Build metadata -----------------------------------------------------------------------

    single<Boolean>(named("isDevBuild")) {
        isDevVersionName(com.riffle.app.BuildConfig.VERSION_NAME)
    }

    // ---- Logging: Logger/InMemoryLogBuffer are bound by loggingKoinModule (core:logging) ------

    // ---- Readium ------------------------------------------------------------------------------

    single { DefaultHttpClient() }
    single {
        AssetRetriever(androidContext().contentResolver, get<DefaultHttpClient>())
    }
    single {
        PublicationOpener(
            DefaultPublicationParser(
                androidContext(),
                get<DefaultHttpClient>(),
                get<AssetRetriever>(),
                pdfFactory = PdfiumDocumentFactory(androidContext()),
            )
        )
    }

    // ---- Ebook CFI ---------------------------------------------------------------------------

    single<EbookCfiTranslatorFactory> {
        EbookCfiTranslatorFactoryImpl(
            downloadsStore = get(named("epubDownloadsStore")),
            cacheStore = get(named("epubCacheStore")),
        )
    }

    // ---- Appearance --------------------------------------------------------------------------

    single<AppearanceCoordinator> {
        AppearanceCoordinatorImpl(
            appThemeStore = get(),
            formattingPreferencesStore = get(),
            timeProvider = get(),
            scope = get(named("applicationCoroutineScope")),
        )
    }

    // ---- Update ------------------------------------------------------------------------------

    single<ApkInstaller> { AndroidApkInstaller(androidContext()) }

    // ---- Annotation sweep -------------------------------------------------------------------

    single<AnnotationSweepEnqueuer> { AnnotationSweepEnqueuerImpl(androidContext()) }

    // ---- Readaloud offline ------------------------------------------------------------------

    single<ReadaloudOfflineDownloader> {
        ReadaloudOfflineDownloaderImpl(androidContext(), get(), get())
    } bind com.riffle.feature.library.ReadaloudOfflineDownloader::class

    // ---- WebDAV banner ticker ----------------------------------------------------------------

    single<Flow<Unit>>(named(AddSourceViewModel.WEBDAV_BANNER_TICKER)) {
        flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        }
    }

    // ---- Shared source-onboarding seams (:feature:source-ui) ---------------------------------
    // The Add-Source screens moved to an android+ios Compose module, so the three things their
    // ViewModels used to reach for directly on Android are bound here instead:
    //   * localized copy — Compose Multiplatform resources from :feature:source-ui
    //   * the WebDAV probe — WebDavAnnotationSyncTargetFactory lives in core/sources' jvmMain and
    //     is not visible from an android+ios commonMain
    //   * the progress sweep kick — WorkManager, Android-only
    //   * BuildConfig dev prefill — Android-only
    single<SourceUiStrings> { ComposeResourceSourceUiStrings }

    single<WebdavConnectionTester> { WebDavTargetConnectionTester(get()) }

    single<ProgressSyncTrigger> {
        val context = androidContext()
        ProgressSyncTrigger { ProgressSyncScheduler.sweepNow(context) }
    }

    single {
        DevSourceDefaults(
            url = com.riffle.app.BuildConfig.DEV_SERVER_URL,
            username = com.riffle.app.BuildConfig.DEV_USERNAME,
            password = com.riffle.app.BuildConfig.DEV_PASSWORD,
        )
    }

    // ---- Playback navigation ----------------------------------------------------------------

    single { NowPlayingNavigator() }
    single { NowPlayingStore() }

    // ---- Audio session connectors (factory — one instance per consumer) ---------------------

    factory<MediaSessionConnector> { DefaultMediaSessionConnector(androidContext()) }

    // ---- Audio source factories / restorers -------------------------------------------------

    // The factory/restorer lists are inlined into their registries rather than bound as
    // single<List<...>>: Koin indexes definitions by ERASED KClass, so two unqualified
    // List<...> definitions silently override each other (the same erasure collision that
    // handed DefaultCatalogRegistry a Map of SourceAdapters).
    single {
        MediaSourceRegistry(
            listOf(
                HttpAudioSourceFactory(),
                FileAudioSourceFactory(),
                BundleAudioSourceFactory(get()),
            ),
        )
    }
    single {
        MediaItemRestorerRegistry(
            listOf(
                StreamingReadaloudItemRestorer(),
                AudiobookHttpItemRestorer(),
                BundleZipItemRestorer(),
            ),
        )
    }

    // ---- Readaloud / audiobook controllers --------------------------------------------------

    single {
        ReadaloudController(
            connector = get<MediaSessionConnector>(),
            applicationScope = get<ApplicationScope>(),
            dispatchers = get(),
            logger = get(),
            clock = get(),
        )
    } bind ReadaloudHandoff::class
    single {
        AudiobookController(
            connector = get<MediaSessionConnector>(),
            applicationScope = get<ApplicationScope>(),
            dispatchers = get(),
            logger = get(),
            clock = get(),
        )
    } bind AudioPlayerInterface::class

    // ---- Readaloud streaming -----------------------------------------------------------------

    single {
        ReadaloudStreamingSessionFactory(
            context = androidContext(),
            audioIdentityResolver = get(),
            catalogRegistry = get(),
            storytellerApi = get(),
            sidecarStore = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            linkRepository = get(),
            dispatchers = get(),
        )
    }

    // ---- Player coordinator -----------------------------------------------------------------

    single { PlayerCoordinator(controller = get(), audioRepository = get(), dispatchers = get()) }
    single<PlayerController> { get<PlayerCoordinator>() }
    single<FiguresInRangeResolver> { NoopFiguresInRangeResolver() }
    single<ResourceFetcher> { NoopResourceFetcher() }
    single<PdfMetadataExtractor> { PdfiumPdfMetadataExtractor(context = androidContext()) }

    // ---- Audiobook state --------------------------------------------------------------------

    factory {
        ReaderSyncFactory(
            linkRepository = get(),
            sourceRepository = get(),
            catalogRegistry = get(),
            indexStore = get(),
            libraryObserver = get(),
            cacheStore = get(named("epubCacheStore")),
            downloadsStore = get(named("epubDownloadsStore")),
            crossEpubIndexBuildTrigger = get(),
            sidecarCache = get(),
            clock = get(),
            logger = get(),
        )
    } bind com.riffle.feature.reader.ReaderSyncFactoryInterface::class
    factory { HighlightsPdfExporter(context = androidContext(), factory = get()) }
    single {
        LibraryTabVisibilityObserver(
            libraryObserver = get(),
            toReadRepository = get(),
            annotationsLibraryRepository = get(),
            sourceRepository = get(),
        )
    }

    single { AudiobookHandoffState() }
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

    // ---- Reader state -----------------------------------------------------------------------

    single { ReaderStateHolder() }
    single { VolumeNavigationController() }
    single { VolumeKeyDispatcher(volumeKeyPreferencesStore = get(), volumeNavigationController = get()) }
    single { CadenceController(dispatchers = get()) }
    single { AutoScrollController(dispatchers = get()) }
    single { HighlightsPublicationFactory() }
    single { ProgressFlushScope(applicationScope = get()) }

    // ---- Download/Import managers -----------------------------------------------------------

    single { DownloadManager(scope = get(named("downloadScope"))) } bind com.riffle.feature.library.DownloadManager::class
    single { BookImportManager(scope = get(named("downloadScope"))) } bind com.riffle.feature.library.BookImportManager::class

    // ---- AssistedFactory replacements -------------------------------------------------------

    factory<FormattingSession.Factory> {
        FormattingSession.Factory { scope ->
            FormattingSession(
                scope = scope,
                formattingPreferencesStoreProvider = get(),
                bookFormattingPreferencesStore = get(),
                wakeLockPreferencesStore = get(),
                listeningPreferencesStore = get(),
                autoScrollController = get(),
                appearanceCoordinator = get(),
            )
        }
    }

    factory<PositionOrchestrator.Factory> {
        PositionOrchestrator.Factory { scope ->
            PositionOrchestrator(scope = scope)
        }
    }

    factory<BookmarksController.Factory> {
        BookmarksController.Factory { scope, onScheduleSync ->
            BookmarksController(
                scope = scope,
                annotationStore = get(),
                onScheduleSync = onScheduleSync,
            )
        }
    }

    factory<WakeLockController.Factory> {
        WakeLockController.Factory { scope, autoScrollState ->
            WakeLockController(
                scope = scope,
                wakeLockPreferencesStore = get(),
                autoScrollState = autoScrollState,
            )
        }
    }

    factory<SearchController.Factory> {
        SearchController.Factory { scope ->
            SearchController(scope = scope, dispatchers = get())
        }
    }

    factory<AnnotationSession.Factory> {
        AnnotationSession.Factory { scope, startLiveSync, scheduleSync, syncOnOpen, syncOnClose, mergeAfterEdit, commitDraftWithNote ->
            AnnotationSession(
                scope = scope,
                annotationStore = get(),
                annotationStatusStore = get(),
                highlightColorPreferencesStore = get(),
                emphasisPreferencesStore = get(),
                progressFlushScope = get(),
                startLiveSync = startLiveSync,
                scheduleSync = scheduleSync,
                syncOnOpen = syncOnOpen,
                syncOnClose = syncOnClose,
                mergeAfterEdit = mergeAfterEdit,
                commitDraftWithNote = commitDraftWithNote,
            )
        }
    }

    factory<ReadaloudSession.Factory> {
        ReadaloudSession.Factory { scope, snapshotLocator ->
            ReadaloudSession(
                scope = scope,
                snapshotLocator = snapshotLocator,
                playerCoordinator = get(),
                readaloudAudioRepository = get(),
                streamingSessionFactory = get(),
                storytellerSyncController = get(),
                audioPlaybackPreferencesStore = get(),
                listeningPreferencesStore = get(),
                audioIdentityResolver = get(),
                readaloudPreferencesStore = get(),
                readaloudResumeStore = get(),
                sidecarStore = get(),
                readingPositionStore = get(),
                readingSyncStore = get(),
                audioSyncStore = get(),
                epubRepository = get(),
                progressFlushScope = get(),
                audiobookHandoffState = get(),
                connectivityObserver = get(),
                nowPlayingStore = get(),
                dispatchers = get(),
                logger = get(),
            )
        }
    }

    factory<ReaderSessionLifecycle.Factory> {
        ReaderSessionLifecycle.Factory { openPublication, cfiStringToLocator ->
            ReaderSessionLifecycle(
                openPublication = openPublication,
                cfiStringToLocator = cfiStringToLocator,
                libraryObserver = get(),
                epubRepository = get(),
                sourceRepository = get(),
                readaloudLinkRepository = get(),
                audioIdentityResolver = get(),
                audioPlaybackPreferencesStore = get(),
                listeningPreferencesStore = get(),
                openReconcileTargets = get(),
                readerSyncFactory = get(),
                annotationStore = get(),
                logger = get(),
                itemProgressPuller = get(),
            )
        }
    }

    // Domain use cases
    single { RefreshLibraries(refresher = get()) }
    single { RefreshLibraryItems(refresher = get(), storytellerSyncer = get(), readaloudReconciler = get(), applicationScope = get()) }
    single { RefreshSeries(refresher = get()) }
    single { RefreshCollections(refresher = get()) }
    single { RecordItemOpened(libraryMutator = get(), readingSessionRepository = get()) }
    single { MarkReadAcrossDimensions(libraryMutator = get(), readingSessionRepository = get(), readaloudLinkRepository = get(), sourceRepository = get()) }
    single { UpdateReadingProgress(libraryMutator = get()) }
    single { ReadaloudReviewActions(mutator = get(), linkRepository = get(), audioIdentityResolver = get(), audioPlaybackPreferencesStore = get()) }
    single { ExtractEpubTocUseCase(epubRepository = get(), publicationOpener = get(), assetRetriever = get(), tocRepository = get(), publicationMetricsRepository = get(), dispatchers = get()) }
    single { ExtractPdfPageCountUseCase(pdfRepository = get(), publicationOpener = get(), assetRetriever = get(), publicationMetricsRepository = get()) }
    single { FetchAudiobookChaptersUseCase(chapterCacheRepository = get()) }
    single<SharedFetchAudiobookChaptersUseCase> { SharedFetchAudiobookChaptersUseCase(chapterCacheRepository = get()) }
    single { SaveLocalFileMetadataOverrideUseCase(overrideDao = get()) }
    single { CopyCoverImageUseCase(context = androidContext()) }

    // feature.library interface adapters — bridge existing implementations to the shared interfaces
    single<EpubTocExtractor> {
        val uc = get<ExtractEpubTocUseCase>()
        object : EpubTocExtractor {
            override suspend fun extract(item: LibraryItem) = uc(item)
            override suspend fun extractDetails(item: LibraryItem): EpubDetails {
                val d = uc.extractDetails(item)
                return EpubDetails(d.tocEntries, d.totalPositions, d.epubVersion)
            }
        }
    }
    single<PdfPageCountExtractor> {
        val uc = get<ExtractPdfPageCountUseCase>()
        object : PdfPageCountExtractor {
            override suspend fun extract(item: LibraryItem) = uc(item)
        }
    }
    single<LocalFileMetadataOverrideSaver> {
        val uc = get<SaveLocalFileMetadataOverrideUseCase>()
        object : LocalFileMetadataOverrideSaver {
            override suspend fun invoke(sourceId: String, sourceItemId: String, title: String?, author: String?, seriesName: String?, seriesIndex: Double?, coverUrl: String?) {
                uc(sourceId, sourceItemId, title, author, seriesName, seriesIndex, coverUrl)
            }
        }
    }
    single<CoverImageCopier> {
        val uc = get<CopyCoverImageUseCase>()
        object : CoverImageCopier {
            override suspend fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String? =
                uc(sourceId, sourceItemId, contentUriString)
        }
    }
    single<WebSourceLibraryItemUpserter> {
        val upserter = get<com.riffle.core.data.websource.WebSourceLibraryItemUpserter>()
        object : WebSourceLibraryItemUpserter {
            override suspend fun upsert(sourceId: String, item: com.riffle.core.catalog.CatalogItem) =
                upserter.upsert(sourceId, item)
        }
    }
}
