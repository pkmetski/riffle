package com.riffle.shared

import com.riffle.core.data.AnnotationsLibraryRepository
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
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookBookmarkStore
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
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.KomgaLibraryApiClient
import com.riffle.core.network.createDefaultHttpClient
import com.riffle.feature.library.CollectionDetailViewModel
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibrarySectionViewModel
import com.riffle.feature.library.SeriesDetailViewModel
import com.riffle.shared.audiobook.IosAudioPlayerBridgeFactory
import com.riffle.shared.audiobook.IosAudiobookPlayerViewModel
import com.riffle.shared.downloads.DownloadsViewModel
import com.riffle.shared.library.AnnotationsListViewModel
import com.riffle.shared.library.IosNoOpAnnotationStore
import com.riffle.shared.library.IosNoOpAnnotationsLibraryRepository
import com.riffle.shared.library.IosNoOpAppThemeStore
import com.riffle.shared.library.IosNoOpApplicationScope
import com.riffle.shared.library.IosNoOpAudiobookBookmarkStore
import com.riffle.shared.library.IosNoOpCoverGridDensityStore
import com.riffle.shared.library.IosNoOpDownloadsRepository
import com.riffle.shared.library.IosNoOpFormattingPreferencesStore
import com.riffle.shared.library.IosNoOpLibraryFilterPreferencesStore
import com.riffle.shared.library.IosNoOpReadaloudLinkRepository
import com.riffle.shared.library.IosNoOpReadaloudReconciler
import com.riffle.shared.library.IosNoOpStorytellerSyncer
import com.riffle.shared.library.LibraryItemDetailViewModel
import com.riffle.shared.library.LibraryItemsViewModel
import com.riffle.shared.reader.IosEpubDownloader
import com.riffle.shared.reader.IosEpubNavigatorBridgeFactory
import com.riffle.shared.reader.IosPdfDownloader
import com.riffle.shared.reader.IosPdfNavigatorBridgeFactory
import com.riffle.shared.settings.SettingsViewModel
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
    single<KomgaLibraryApi> { KomgaLibraryApiClient(get()) }

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
    single { DownloadsViewModel(get()) }
    single { SettingsViewModel(get(), get(), get()) }
    single<AnnotationStore> { IosNoOpAnnotationStore() }
    single<AudiobookBookmarkStore> { IosNoOpAudiobookBookmarkStore() }
    single<ReadaloudLinkRepository> { IosNoOpReadaloudLinkRepository() }
    single<AnnotationsLibraryRepository> { IosNoOpAnnotationsLibraryRepository() }

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
