package com.riffle.app.di

import com.riffle.app.feature.annotations.AnnotationsListViewModel
import com.riffle.app.feature.audiobook.AudiobookPlayerViewModel
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
import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.ExtractEpubTocUseCase
import com.riffle.app.feature.reader.PdfReaderViewModel
import com.riffle.app.feature.reader.cbz.CbzReaderViewModel
import com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader
import com.riffle.app.feature.server.AddSourceViewModel
import com.riffle.app.feature.server.SelectLibrariesViewModel
import com.riffle.app.feature.server.SourceSetupViewModel
import com.riffle.app.feature.server.SourceTypePickerViewModel
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.annotationsync.AnnotationSyncMaintenanceViewModel
import com.riffle.app.feature.settings.debug.DebugLogViewModel
import com.riffle.app.feature.settings.dictionary.DictionaryPacksViewModel
import com.riffle.app.feature.settings.readaloud.ReadaloudMatchesViewModel
import com.riffle.app.feature.source.chitanka.AddChitankaViewModel
import com.riffle.app.feature.source.chitanka.ChitankaBrowseViewModel
import com.riffle.app.feature.source.chitanka.friendlyErrorMessage as chitankaFriendlyError
import com.riffle.app.feature.source.gutenberg.AddGutenbergViewModel
import com.riffle.app.feature.source.gutenberg.GutenbergBrowseViewModel
import com.riffle.app.feature.source.gutenberg.friendlyErrorMessage as gutenbergFriendlyError
import com.riffle.app.feature.source.localfiles.AddLocalFilesViewModel
import com.riffle.app.feature.source.radioes.AddRadioEsViewModel
import com.riffle.app.feature.source.radioes.RadioEsBrowseViewModel
import com.riffle.app.feature.source.websource.WebSourceLibraryViewModel
import com.riffle.app.feature.update.ChangelogViewModel
import com.riffle.app.feature.update.StartupUpdateViewModel
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.common.Clock
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ReadaloudSidecarPrefetcher
import com.riffle.core.data.ReadaloudSidecarStore
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
import kotlinx.coroutines.flow.Flow
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import com.riffle.core.data.di.SOURCE_ADAPTERS_BY_SOURCE_TYPE
import org.koin.core.qualifier.named
import org.koin.dsl.module

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
    viewModel {
        DictionaryPacksViewModel(
            packStore = get(),
            downloader = get(),
            downloadManager = get(),
        )
    }
}

private val serverViewModelModule = module {
    viewModel {
        AddSourceViewModel(
            context = androidContext(),
            repository = get(),
            authenticators = get(named(SOURCE_ADAPTERS_BY_SOURCE_TYPE)),
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
    viewModel { AddRadioEsViewModel(installer = get()) }
    viewModel {
        RadioEsBrowseViewModel(
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

private val readerViewModelModule = module {
    viewModel {
        EpubReaderViewModel(
            application = androidApplication(),
            savedStateHandle = get(),
            libraryObserver = get(),
            updateReadingProgressUseCase = get(),
            epubRepository = get(),
            assetRetriever = get(),
            publicationOpener = get(),
            readingSessionRepository = get(),
            timeProvider = get(),
            readerStateHolder = get(),
            readaloudAudioRepository = get(),
            streamingSessionFactory = get(),
            playerCoordinator = get(),
            storytellerSyncController = get(),
            sourceRepository = get(),
            readaloudLinkRepository = get(),
            audioIdentityResolver = get(),
            audioPlaybackPreferencesStore = get(),
            listeningPreferencesStore = get(),
            connectivityObserver = get(),
            readerSyncFactory = get(),
            readingPositionStore = get(),
            readingSyncStore = get(),
            audioSyncStore = get(),
            openReconcileTargets = get(),
            readaloudResumeStore = get(),
            annotationStore = get(),
            emphasisPreferencesStore = get(),
            annotationSyncController = get(),
            nowPlayingStore = get(),
            progressFlushScope = get(),
            readaloudPreferencesStore = get(),
            readingSpeedStore = get(),
            audiobookHandoffState = get(),
            sidecarStore = get(),
            formattingSessionFactory = get(),
            bookmarksControllerFactory = get(),
            searchControllerFactory = get(),
            wakeLockControllerFactory = get(),
            volumeKeyDispatcher = get(),
            cadenceController = get(),
            positionOrchestratorFactory = get(),
            annotationSessionFactory = get(),
            readaloudSessionFactory = get(),
            readerSessionLifecycleFactory = get(),
            logger = get(),
            clock = get(),
            dispatchers = get(),
            highlightsPublicationFactory = get(),
            annotationDao = get(),
            libraryItemDao = get(),
            highlightsResumeStore = get(),
            tocRepository = get(),
            figuresInRangeResolver = get(),
            catalogRegistry = get(),
            epubDownloadsStore = get(named("epubDownloadsStore")),
            epubCacheStore = get(named("epubCacheStore")),
            pdfExporter = get(),
            dictionaryRepository = get(),
            packStore = get(),
            packDownloader = get(),
            downloadManager = get(),
            progressSweep = get(),
        )
    }
    viewModel {
        PdfReaderViewModel(
            application = androidApplication(),
            savedStateHandle = get(),
            libraryObserver = get(),
            updateReadingProgressUseCase = get(),
            pdfRepository = get(),
            assetRetriever = get(),
            publicationOpener = get(),
            wakeLockPreferencesStore = get(),
            readingSessionRepository = get(),
            volumeNavigationController = get(),
            readerStateHolder = get(),
            annotationStore = get(),
            sourceRepository = get(),
            formattingSessionFactory = get(),
            volumeKeyDispatcher = get(),
            clock = get(),
            readingSpeedStore = get(),
            catalogRegistry = get(),
        )
    }
    viewModel {
        CbzReaderViewModel(
            application = androidApplication(),
            savedStateHandle = get(),
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
            panelReportRepository = get(),
        )
    }
}

private val audiobookViewModelModule = module {
    viewModel {
        AudiobookPlayerViewModel(
            savedStateHandle = get(),
            audiobookRepository = get(),
            audiobookDownloadRepository = get(),
            audiobookCacheRepository = get(),
            bundleAudiobookSource = get(),
            libraryObserver = get(),
            updateReadingProgressUseCase = get(),
            sourceRepository = get(),
            tokenStorage = get(),
            controller = get(),
            readaloudController = get(),
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
            progressSweep = get(),
        )
    }
}

private val annotationsViewModelModule = module {
    viewModel {
        AnnotationsListViewModel(
            sourceRepository = get(),
            repo = get(),
            tokenStorage = get(),
            savedStateHandle = get(),
        )
    }
}

private val updateViewModelModule = module {
    viewModel {
        StartupUpdateViewModel(
            appUpdateRepository = get(),
            appUpdatePreferencesStore = get(),
            clock = get(),
            isDevBuild = get(named("isDevBuild")),
        )
    }
    viewModel {
        ChangelogViewModel(
            appUpdateRepository = get(),
        )
    }
}

internal fun riffleViewModelKoinModules(): List<Module> = listOf(
    appKoinModule,
    libraryViewModelModule,
    navigationViewModelModule,
    settingsViewModelModule,
    serverViewModelModule,
    sourceViewModelModule,
    downloadsViewModelModule,
    readerViewModelModule,
    audiobookViewModelModule,
    annotationsViewModelModule,
    updateViewModelModule,
)
