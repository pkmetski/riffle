package com.riffle.shared

import com.riffle.core.common.Clock
import com.riffle.core.common.IosSystemClock
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.data.AnnotationsLibraryRepositoryImpl
import com.riffle.core.data.IosLastOpenedLibraryStoreImpl
import com.riffle.core.data.IosLibraryItemOfflineAvailabilityImpl
import com.riffle.core.data.IosLibraryObserverImpl
import com.riffle.core.data.IosLibraryRefresherImpl
import com.riffle.core.data.IosLibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.IosPlaylistsRepositoryImpl
import com.riffle.core.data.IosSourceRepositoryImpl
import com.riffle.core.data.IosToReadRepositoryImpl
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.di.iosDataModule
import com.riffle.core.data.di.iosDatabaseModule
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.logging.iosLoggingModule
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.KomgaCbzApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.KomgaLibraryApiClient
import com.riffle.core.network.createDefaultHttpClient
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.CollectionDetailViewModel
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.LibrarySectionViewModel
import com.riffle.feature.library.SeriesDetailViewModel
import com.riffle.shared.audiobook.IosAudioPlayerBridgeFactory
import com.riffle.shared.audiobook.IosAudiobookPlayerViewModel
import com.riffle.shared.library.IosNoOpAppThemeStore
import com.riffle.shared.library.IosNoOpApplicationScope
import com.riffle.shared.library.IosNoOpAudiobookBookmarkStore
import com.riffle.shared.library.IosNoOpContentCacheSettingsStore
import com.riffle.shared.library.IosNoOpCoverGridDensityStore
import com.riffle.shared.library.IosNoOpDownloadsRepository
import com.riffle.shared.library.IosNoOpContentCacheSettingsStore
import com.riffle.shared.library.IosNoOpFormattingPreferencesStore
import com.riffle.shared.library.IosNoOpLibraryFilterPreferencesStore
import com.riffle.shared.library.IosNoOpReadaloudLinkRepository
import com.riffle.shared.library.IosNoOpReadaloudReconciler
import com.riffle.shared.library.IosNoOpReadaloudSidecarDownloads
import com.riffle.shared.library.IosNoOpStorytellerSyncer
import com.riffle.shared.library.LibraryItemDetailViewModel
import com.riffle.shared.reader.IosCbzDownloader
import com.riffle.shared.reader.IosEpubDownloader
import com.riffle.shared.reader.IosEpubNavigatorBridgeFactory
import com.riffle.shared.reader.IosPdfDownloader
import com.riffle.shared.reader.IosPdfNavigatorBridgeFactory
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.feature.settings.AppVersion
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.shared.settings.IosNoOpAnnotationSyncConfigStore
import com.riffle.shared.settings.IosNoOpAppUpdatePreferencesStore
import com.riffle.shared.settings.IosNoOpAppUpdateRepository
import com.riffle.shared.settings.IosNoOpComicFormattingPreferencesStore
import com.riffle.shared.settings.IosNoOpCrashReportRepository
import com.riffle.shared.settings.IosNoOpDeveloperOptionsRepository
import com.riffle.shared.settings.IosNoOpLibraryOrderPreferencesStore
import com.riffle.shared.settings.IosNoOpListeningPreferencesStore
import com.riffle.shared.settings.IosNoOpLocalFilesFolderDao
import com.riffle.shared.settings.IosNoOpLocalFilesFolderHealthChecker
import com.riffle.shared.settings.IosNoOpLocalFilesFolderRepository
import com.riffle.shared.settings.IosNoOpLocalFilesScannerInterface
import com.riffle.shared.settings.IosNoOpReadaloudPreferencesStore
import com.riffle.shared.settings.IosNoOpReadaloudReviewRepository
import com.riffle.shared.settings.IosNoOpVolumeKeyPreferencesStore
import com.riffle.shared.settings.IosNoOpWakeLockPreferencesStore
import org.koin.dsl.module
import org.koin.core.context.startKoin as koinStartKoin

private fun iosLibraryModule(
    navigatorBridgeFactory: IosEpubNavigatorBridgeFactory,
    audioPlayerBridgeFactory: IosAudioPlayerBridgeFactory,
    pdfNavigatorBridgeFactory: IosPdfNavigatorBridgeFactory,
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
    single<LibraryObserver> { IosLibraryObserverImpl(get(), get()) }
    single<LibraryRefresher> { IosLibraryRefresherImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<LastOpenedLibraryStore> { IosLastOpenedLibraryStoreImpl() }
    single<LibraryVisibilityPreferencesStore> { IosLibraryVisibilityPreferencesStoreImpl() }
    single { RefreshLibraries(get()) }
    single { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    single { DrawerViewModel(get(), get(), get(), get()) }
    single { AddAbsSourceViewModel(get(), get(), get()) }

    // EPUB reader
    single<IosEpubNavigatorBridgeFactory> { navigatorBridgeFactory }
    single { IosEpubDownloader(get(), get(), get()) }

    // PDF reader
    single<IosPdfNavigatorBridgeFactory> { pdfNavigatorBridgeFactory }
    single { IosPdfDownloader(get(), get(), get()) }

    // CBZ reader
    single { IosCbzDownloader(get(), get(), get()) }

    // Audiobook player
    single<AbsPlaybackApi> { get<AbsApiClient>() }
    single<IosAudioPlayerBridgeFactory> { audioPlayerBridgeFactory }
    factory { params ->
        IosAudiobookPlayerViewModel(
            itemId = params.get(),
            sourceId = params.get(),
            bridgeFactory = get(),
            absPlaybackApi = get(),
            sourceRepository = get(),
            tokenStorage = get(),
        )
    }

    single<PlaylistsRepository> { IosPlaylistsRepositoryImpl(get(), get(), get(), get()) }
    single<ToReadRepository> { IosToReadRepositoryImpl(get(), get(), get(), get()) }
    single<LibraryItemOfflineAvailability> { IosLibraryItemOfflineAvailabilityImpl(get()) }
    single<StorytellerReadaloudCacheSyncer> { IosNoOpStorytellerSyncer }
    single<ReadaloudLinkReconciler> { IosNoOpReadaloudReconciler }
    single<ApplicationScope> { IosNoOpApplicationScope }
    single { RefreshLibraryItems(get(), get(), get(), get()) }
    single { RefreshCollections(get()) }
    single { RefreshSeries(get()) }
    single<CoverGridDensityStore> { IosNoOpCoverGridDensityStore() }
    single<LibraryFilterPreferencesStore> { IosNoOpLibraryFilterPreferencesStore() }
    single<AppThemeStore> { IosNoOpAppThemeStore() }
    single<FormattingPreferencesStore> { IosNoOpFormattingPreferencesStore() }
    single<DownloadsRepository> { IosNoOpDownloadsRepository() }
    single<ContentCacheSettingsStore> { IosNoOpContentCacheSettingsStore() }
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
    single<AppUpdatePreferencesStore> { IosNoOpAppUpdatePreferencesStore() }
    single<WakeLockPreferencesStore> { IosNoOpWakeLockPreferencesStore() }
    single<VolumeKeyPreferencesStore> { IosNoOpVolumeKeyPreferencesStore() }
    single<ListeningPreferencesStore> { IosNoOpListeningPreferencesStore() }
    single<LibraryOrderPreferencesStore> { IosNoOpLibraryOrderPreferencesStore() }
    single<ReadaloudPreferencesStore> { IosNoOpReadaloudPreferencesStore() }
    single<ReadaloudReviewRepository> { IosNoOpReadaloudReviewRepository }
    single<DeveloperOptionsRepository> { IosNoOpDeveloperOptionsRepository() }
    single<AnnotationSyncConfigStore> { IosNoOpAnnotationSyncConfigStore }
    single { com.riffle.core.sync.AnnotationSyncStatusStore() }
    single<ComicFormattingPreferencesStore> { IosNoOpComicFormattingPreferencesStore() }
    single {
        SettingsViewModel(
            appVersion = AppVersion(name = "iOS", code = 0),
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
            localFilesFolderDao = IosNoOpLocalFilesFolderDao,
            localFilesFolderRepository = IosNoOpLocalFilesFolderRepository,
            localFilesScanner = IosNoOpLocalFilesScannerInterface,
            localFilesFolderHealthChecker = IosNoOpLocalFilesFolderHealthChecker,
            comicFormattingPreferencesStore = get(),
            developerOptionsRepository = get(),
            annotationSyncConfigStore = get(),
            annotationSyncStatusStore = get(),
            annotationDao = get(),
        )
    }
    single<Clock> { IosSystemClock }
    single<AnnotationStore> { AnnotationStoreImpl(dao = get(), deviceIdStore = get(), clock = get()) }
    single<AudiobookBookmarkStore> { IosNoOpAudiobookBookmarkStore() }
    single<ReadaloudLinkRepository> { IosNoOpReadaloudLinkRepository() }
    single<AnnotationsLibraryRepository> { AnnotationsLibraryRepositoryImpl(annotationDao = get(), libraryItemDao = get()) }

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
            sourceRepository = get(),
            tokenStorage = get(),
            toReadRepository = get(),
            connectivityObserver = get(),
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
) {
    koinStartKoin {
        modules(
            iosLoggingModule,
            iosDataModule,
            iosDatabaseModule,
            iosLibraryModule(navigatorBridgeFactory, audioPlayerBridgeFactory, pdfNavigatorBridgeFactory),
        )
    }
}
