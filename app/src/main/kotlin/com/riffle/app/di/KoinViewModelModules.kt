package com.riffle.app.di

import com.riffle.app.feature.downloads.DownloadsViewModel
import com.riffle.app.feature.library.AnnotationSearchViewModel
import com.riffle.app.feature.library.BookImportManager
import com.riffle.app.feature.library.CollectionDetailViewModel
import com.riffle.app.feature.library.DownloadManager
import com.riffle.app.feature.library.ExtractPdfPageCountUseCase
import com.riffle.app.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.app.feature.library.FilteredBooksViewModel
import com.riffle.app.feature.library.LibraryItemDetailViewModel
import com.riffle.app.feature.library.LibraryItemsViewModel
import com.riffle.app.feature.library.LibrarySectionViewModel
import com.riffle.app.feature.library.LibraryTabVisibilityObserver
import com.riffle.app.feature.library.LibraryTabVisibilityViewModel
import com.riffle.app.feature.library.SeriesDetailViewModel
import com.riffle.app.feature.library.playlists.PlaylistDetailViewModel
import com.riffle.app.feature.navigation.HomeViewModel
import com.riffle.app.feature.navigation.NavigationDrawerViewModel
import com.riffle.app.feature.reader.ExtractEpubTocUseCase
import com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader
import com.riffle.app.feature.server.AddSourceViewModel
import com.riffle.app.feature.server.SelectLibrariesViewModel
import com.riffle.app.feature.server.SourceSetupViewModel
import com.riffle.app.feature.server.SourceTypePickerViewModel
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.annotationsync.AnnotationSyncMaintenanceViewModel
import com.riffle.app.feature.settings.debug.DebugLogViewModel
import com.riffle.app.feature.settings.readaloud.ReadaloudMatchesViewModel
import com.riffle.app.feature.source.chitanka.AddChitankaViewModel
import com.riffle.app.feature.source.chitanka.ChitankaBrowseViewModel
import com.riffle.app.feature.source.chitanka.friendlyErrorMessage as chitankaFriendlyError
import com.riffle.app.feature.source.gutenberg.AddGutenbergViewModel
import com.riffle.app.feature.source.gutenberg.GutenbergBrowseViewModel
import com.riffle.app.feature.source.gutenberg.friendlyErrorMessage as gutenbergFriendlyError
import com.riffle.app.feature.source.localfiles.AddLocalFilesViewModel
import com.riffle.app.feature.source.websource.WebSourceLibraryViewModel
import com.riffle.app.playback.NowPlayingNavigator
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.common.Clock
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.ReadaloudSidecarPrefetcher
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.credentialed.CredentialedAuthenticator
import com.riffle.core.data.localfiles.CopyCoverImageUseCase
import com.riffle.core.data.localfiles.LocalFilesFolderHealthChecker
import com.riffle.core.data.localfiles.LocalFilesFolderRepository
import com.riffle.core.data.localfiles.LocalFilesScanner
import com.riffle.core.data.localfiles.LocalFilesSourceInstaller
import com.riffle.core.data.localfiles.SaveLocalFileMetadataOverrideUseCase
import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.ReadaloudReviewActions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.models.SourceType
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory
import com.riffle.core.sync.AnnotationSyncStatusStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val bridgeModule = module {
    single { EntryPointAccessors.fromApplication(androidContext(), KoinBridgeEntryPoint::class.java) }
}

private val bridgeDepsModule = module {
    single<LibraryObserver> { get<KoinBridgeEntryPoint>().libraryObserver() }
    single<RefreshLibraryItems> { get<KoinBridgeEntryPoint>().refreshLibraryItems() }
    single<RefreshSeries> { get<KoinBridgeEntryPoint>().refreshSeries() }
    single<RefreshCollections> { get<KoinBridgeEntryPoint>().refreshCollections() }
    single<RefreshLibraries> { get<KoinBridgeEntryPoint>().refreshLibraries() }
    single<SourceRepository> { get<KoinBridgeEntryPoint>().sourceRepository() }
    single<TokenStorage> { get<KoinBridgeEntryPoint>().tokenStorage() }
    single<LibraryItemOfflineAvailability> { get<KoinBridgeEntryPoint>().libraryItemOfflineAvailability() }
    single<ConnectivityObserver> { get<KoinBridgeEntryPoint>().connectivityObserver() }
    single<ToReadRepository> { get<KoinBridgeEntryPoint>().toReadRepository() }
    single<PlaylistsRepository> { get<KoinBridgeEntryPoint>().playlistsRepository() }
    single<ReadaloudLinkRepository> { get<KoinBridgeEntryPoint>().readaloudLinkRepository() }
    single<CoverGridDensityStore> { get<KoinBridgeEntryPoint>().coverGridDensityStore() }
    single<LibraryFilterPreferencesStore> { get<KoinBridgeEntryPoint>().libraryFilterPreferencesStore() }
    single<AnnotationStore> { get<KoinBridgeEntryPoint>().annotationStore() }
    single<AudiobookBookmarkStore> { get<KoinBridgeEntryPoint>().audiobookBookmarkStore() }
    single<AnnotationsLibraryRepository> { get<KoinBridgeEntryPoint>().annotationsLibraryRepository() }
    single<DispatcherProvider> { get<KoinBridgeEntryPoint>().dispatchers() }
    single<LibraryVisibilityPreferencesStore> { get<KoinBridgeEntryPoint>().libraryVisibilityPreferencesStore() }
    single<LastOpenedLibraryStore> { get<KoinBridgeEntryPoint>().lastOpenedLibraryStore() }
    single<LibraryOrderPreferencesStore> { get<KoinBridgeEntryPoint>().libraryOrderPreferencesStore() }
    single<CatalogRegistry> { get<KoinBridgeEntryPoint>().catalogRegistry() }
    single<NowPlayingNavigator> { get<KoinBridgeEntryPoint>().nowPlayingNavigator() }
    single<NowPlayingStore> { get<KoinBridgeEntryPoint>().nowPlayingStore() }
    single<RecordItemOpened> { get<KoinBridgeEntryPoint>().recordItemOpened() }
    single<UpdateReadingProgress> { get<KoinBridgeEntryPoint>().updateReadingProgress() }
    single<MarkReadAcrossDimensions> { get<KoinBridgeEntryPoint>().markReadAcrossDimensions() }
    single<EpubRepository> { get<KoinBridgeEntryPoint>().epubRepository() }
    single<EbookCfiTranslatorFactory> { get<KoinBridgeEntryPoint>().ebookCfiTranslatorFactory() }
    single<AudiobookPositionStore> { get<KoinBridgeEntryPoint>().audiobookPositionStore() }
    single<PdfRepository> { get<KoinBridgeEntryPoint>().pdfRepository() }
    single<CbzRepository> { get<KoinBridgeEntryPoint>().cbzRepository() }
    single<AudiobookDownloadRepository> { get<KoinBridgeEntryPoint>().audiobookDownloadRepository() }
    single<AudiobookCacheRepository> { get<KoinBridgeEntryPoint>().audiobookCacheRepository() }
    single<LocalAvailabilityEvents> { get<KoinBridgeEntryPoint>().localAvailabilityEvents() }
    single<ReadaloudAudioRepository> { get<KoinBridgeEntryPoint>().readaloudAudioRepository() }
    single<ReadaloudOfflineDownloader> { get<KoinBridgeEntryPoint>().readaloudOfflineDownloader() }
    single<CrossEpubIndexBuildTrigger> { get<KoinBridgeEntryPoint>().crossEpubIndexBuildTrigger() }
    single<ReadaloudSidecarPrefetcher> { get<KoinBridgeEntryPoint>().readaloudSidecarPrefetcher() }
    factory<ExtractEpubTocUseCase> { get<KoinBridgeEntryPoint>().extractEpubTocUseCase() }
    factory<ExtractPdfPageCountUseCase> { get<KoinBridgeEntryPoint>().extractPdfPageCountUseCase() }
    factory<FetchAudiobookChaptersUseCase> { get<KoinBridgeEntryPoint>().fetchAudiobookChaptersUseCase() }
    single<LibraryRefresher> { get<KoinBridgeEntryPoint>().libraryRefresher() }
    factory<SaveLocalFileMetadataOverrideUseCase> { get<KoinBridgeEntryPoint>().saveLocalFileMetadataOverride() }
    factory<CopyCoverImageUseCase> { get<KoinBridgeEntryPoint>().copyCoverImage() }
    single<ReadingSpeedStore> { get<KoinBridgeEntryPoint>().readingSpeedStore() }
    single<WebSourceLibraryItemUpserter> { get<KoinBridgeEntryPoint>().webSourceLibraryItemUpserter() }
    single<WebSourceItemGate> { get<KoinBridgeEntryPoint>().webSourceItemGate() }
    single<DownloadManager> { get<KoinBridgeEntryPoint>().downloadManager() }
    single<BookImportManager> { get<KoinBridgeEntryPoint>().bookImportManager() }
    single<DownloadsRepository> { get<KoinBridgeEntryPoint>().downloadsRepository() }
    single<ReadaloudSidecarStore> { get<KoinBridgeEntryPoint>().readaloudSidecarStore() }
    single<ContentCacheSettingsStore> { get<KoinBridgeEntryPoint>().contentCacheSettingsStore() }
    single<CrashReportRepository> { get<KoinBridgeEntryPoint>().crashReportRepository() }
    single<FormattingPreferencesStore> { get<KoinBridgeEntryPoint>().formattingPreferencesStore() }
    single<WakeLockPreferencesStore> { get<KoinBridgeEntryPoint>().wakeLockPreferencesStore() }
    single<VolumeKeyPreferencesStore> { get<KoinBridgeEntryPoint>().volumeKeyPreferencesStore() }
    single<ListeningPreferencesStore> { get<KoinBridgeEntryPoint>().listeningPreferencesStore() }
    single<AppThemeStore> { get<KoinBridgeEntryPoint>().appThemeStore() }
    single<ReadaloudReviewRepository> { get<KoinBridgeEntryPoint>().readaloudReviewRepository() }
    single<AppUpdateRepository> { get<KoinBridgeEntryPoint>().appUpdateRepository() }
    single<AppUpdatePreferencesStore> { get<KoinBridgeEntryPoint>().appUpdatePreferencesStore() }
    single<ReadaloudPreferencesStore> { get<KoinBridgeEntryPoint>().readaloudPreferencesStore() }
    single<LocalFilesFolderDao> { get<KoinBridgeEntryPoint>().localFilesFolderDao() }
    single<LocalFilesFolderRepository> { get<KoinBridgeEntryPoint>().localFilesFolderRepository() }
    single<LocalFilesScanner> { get<KoinBridgeEntryPoint>().localFilesScanner() }
    single<LocalFilesSourceInstaller> { get<KoinBridgeEntryPoint>().localFilesSourceInstaller() }
    single<LocalFilesFolderHealthChecker> { get<KoinBridgeEntryPoint>().localFilesFolderHealthChecker() }
    single<ComicFormattingPreferencesStore> { get<KoinBridgeEntryPoint>().comicFormattingPreferencesStore() }
    single<DeveloperOptionsRepository> { get<KoinBridgeEntryPoint>().developerOptionsRepository() }
    single<AnnotationSyncConfigStore> { get<KoinBridgeEntryPoint>().annotationSyncConfigStore() }
    single<AnnotationSyncStatusStore> { get<KoinBridgeEntryPoint>().annotationSyncStatusStore() }
    single<AnnotationDao> { get<KoinBridgeEntryPoint>().annotationDao() }
    single<InMemoryLogBuffer> { get<KoinBridgeEntryPoint>().inMemoryLogBuffer() }
    single<AnnotationSyncMaintenance> { get<KoinBridgeEntryPoint>().annotationSyncMaintenance() }
    single<DeviceIdStore> { get<KoinBridgeEntryPoint>().deviceIdStore() }
    single<DeviceLabelStore> { get<KoinBridgeEntryPoint>().deviceLabelStore() }
    single<DeviceLabelResolver> { get<KoinBridgeEntryPoint>().deviceLabelResolver() }
    single<AnnotationSweepEnqueuer> { get<KoinBridgeEntryPoint>().annotationSweepEnqueuer() }
    single<ReadaloudReviewActions> { get<KoinBridgeEntryPoint>().readaloudReviewActions() }
    single<WebDavAnnotationSyncTargetFactory> { get<KoinBridgeEntryPoint>().webDavAnnotationSyncTargetFactory() }
    single<StorytellerReadaloudSyncer> { get<KoinBridgeEntryPoint>().storytellerReadaloudSyncer() }
    single<ReadaloudMatchingService> { get<KoinBridgeEntryPoint>().readaloudMatchingService() }
    single<Map<SourceType, @JvmSuppressWildcards CredentialedAuthenticator>> { get<KoinBridgeEntryPoint>().authenticators() }
    single<Clock> { get<KoinBridgeEntryPoint>().clock() }
    single<Flow<Unit>>(named(AddSourceViewModel.WEBDAV_BANNER_TICKER)) { get<KoinBridgeEntryPoint>().webdavBannerTicker() }
    single<SingletonWebSourceInstaller> { get<KoinBridgeEntryPoint>().singletonWebSourceInstaller() }
    single<LibraryTabVisibilityObserver> { get<KoinBridgeEntryPoint>().libraryTabVisibilityObserver() }
}

private val libraryViewModelModule = module {
    viewModel {
        LibraryItemsViewModel(
            savedStateHandle = get(),
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
    viewModel {
        LibraryItemDetailViewModel(
            savedStateHandle = get(),
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
            extractEpubTocUseCase = get(),
            extractPdfPageCountUseCase = get(),
            fetchAudiobookChaptersUseCase = get(),
            catalogRegistry = get(),
            libraryRefresher = get(),
            saveLocalFileMetadataOverride = get(),
            copyCoverImage = get(),
            readingSpeedStore = get(),
            webSourceLibraryItemUpserter = get(),
        )
    }
    viewModel {
        LibrarySectionViewModel(
            savedStateHandle = get(),
            libraryObserver = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }
    viewModel {
        FilteredBooksViewModel(
            savedStateHandle = get(),
            libraryObserver = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            offlineAvailability = get(),
            connectivityObserver = get(),
            readaloudLinkRepository = get(),
        )
    }
    viewModel {
        SeriesDetailViewModel(
            savedStateHandle = get(),
            libraryObserver = get(),
            refreshSeriesUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            offlineAvailability = get(),
            connectivityObserver = get(),
        )
    }
    viewModel {
        CollectionDetailViewModel(
            savedStateHandle = get(),
            libraryObserver = get(),
            refreshCollectionsUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            offlineAvailability = get(),
            connectivityObserver = get(),
            dispatchers = get(),
        )
    }
    viewModel {
        AnnotationSearchViewModel(
            savedStateHandle = get(),
            libraryObserver = get(),
            annotationStore = get(),
            audiobookBookmarkStore = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }
    viewModel {
        PlaylistDetailViewModel(
            savedStateHandle = get(),
            playlistsRepository = get(),
            libraryObserver = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }
    viewModel {
        LibraryTabVisibilityViewModel(
            savedStateHandle = get(),
            observer = get(),
        )
    }
}

private val navigationViewModelModule = module {
    viewModel {
        HomeViewModel(
            sourceRepository = get(),
            libraryObserver = get(),
            refreshLibraries = get(),
            visibilityStore = get(),
            lastOpenedLibraryStore = get(),
            dispatchers = get(),
        )
    }
    viewModel {
        NavigationDrawerViewModel(
            sourceRepository = get(),
            libraryObserver = get(),
            visibilityStore = get(),
            orderStore = get(),
            lastOpenedLibraryStore = get(),
            connectivityObserver = get(),
            catalogRegistry = get(),
            nowPlayingNavigator = get(),
            nowPlayingStore = get(),
        )
    }
}

private val settingsViewModelModule = module {
    viewModel {
        SettingsViewModel(
            context = androidContext(),
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
            localFilesSourceInstaller = get(),
            localFilesFolderHealthChecker = get(),
            comicFormattingPreferencesStore = get(),
            developerOptionsRepository = get(),
            annotationSyncConfigStore = get(),
            annotationSyncStatusStore = get(),
            annotationDao = get(),
        )
    }
    viewModel {
        DebugLogViewModel(
            application = androidApplication(),
            buffer = get(),
        )
    }
    viewModel {
        AnnotationSyncMaintenanceViewModel(
            configStore = get(),
            maintenance = get(),
            deviceIdStore = get(),
            deviceLabelStore = get(),
            deviceLabelResolver = get(),
            sourceRepository = get(),
            statusStore = get(),
        )
    }
    viewModel {
        ReadaloudMatchesViewModel(
            savedStateHandle = get(),
            reviewRepository = get(),
            reviewActions = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }
}

private val serverViewModelModule = module {
    viewModel {
        AddSourceViewModel(
            context = androidContext(),
            repository = get(),
            authenticators = get(),
            webdavConfigStore = get(),
            webdavTargetFactory = get(),
            webdavStatusStore = get(),
            sweepEnqueuer = get(),
            storytellerSyncer = get(),
            readaloudMatcher = get(),
            tokenStorage = get(),
            clock = get(),
            annotationDao = get(),
            bannerTicker = get(named(AddSourceViewModel.WEBDAV_BANNER_TICKER)),
            savedStateHandle = get(),
        )
    }
    viewModel { SourceSetupViewModel() }
    viewModel {
        SelectLibrariesViewModel(
            context = androidContext(),
            repository = get(),
        )
    }
    viewModel { SourceTypePickerViewModel(sourceRepository = get()) }
}

private val sourceViewModelModule = module {
    viewModel {
        ChitankaBrowseViewModel(
            savedStateHandle = get(),
            sourceRepository = get(),
            catalogRegistry = get(),
            libraryItemUpserter = get(),
            webSourceItemGate = get(),
            coverGridDensityStore = get(),
            libraryFilterPreferencesStore = get(),
            libraryObserver = get(),
        )
    }
    viewModel { AddChitankaViewModel(installer = get()) }
    viewModel {
        GutenbergBrowseViewModel(
            savedStateHandle = get(),
            sourceRepository = get(),
            catalogRegistry = get(),
            libraryItemUpserter = get(),
            webSourceItemGate = get(),
            coverGridDensityStore = get(),
            libraryFilterPreferencesStore = get(),
            libraryObserver = get(),
        )
    }
    viewModel { AddGutenbergViewModel(installer = get()) }
    viewModel { AddLocalFilesViewModel(installer = get()) }
    viewModel {
        WebSourceLibraryViewModel(
            savedStateHandle = get(),
            libraryObserver = get(),
            toReadRepository = get(),
        )
    }
}

private val downloadsViewModelModule = module {
    viewModel {
        DownloadsViewModel(
            downloadsRepository = get(),
            libraryObserver = get(),
            sourceRepository = get(),
            readaloudLinkRepository = get(),
            sidecarStore = get(),
            contentCacheSettingsStore = get(),
        )
    }
}

internal fun riffleViewModelKoinModules(): List<Module> = listOf(
    bridgeModule,
    bridgeDepsModule,
    libraryViewModelModule,
    navigationViewModelModule,
    settingsViewModelModule,
    serverViewModelModule,
    sourceViewModelModule,
    downloadsViewModelModule,
)
