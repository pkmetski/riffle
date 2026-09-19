package com.riffle.core.data.di

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.Clock
import com.riffle.core.common.FileStore
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.IosBookComicFormattingPreferencesStoreImpl
import com.riffle.core.data.IosCatalogProgressRemoteFactory
import com.riffle.core.data.IosComicFormattingPreferencesStoreImpl
import com.riffle.core.data.IosConnectivityObserver
import com.riffle.core.data.IosCoverGridDensityStoreImpl
import com.riffle.core.data.IosDeviceIdStoreImpl
import com.riffle.core.data.IosDeviceLabelResolver
import com.riffle.core.data.IosFileStore
import com.riffle.core.data.IosFormattingPreferencesStoreImpl
import com.riffle.core.data.IosLibraryFilterPreferencesStoreImpl
import com.riffle.core.data.IosLibraryMutatorImpl
import com.riffle.core.data.IosLibraryOrderPreferencesStoreImpl
import com.riffle.core.data.IosListeningPreferencesStoreImpl
import com.riffle.core.data.ReadaloudResumeStoreImpl
import com.riffle.core.data.IosReadaloudPreferencesStoreImpl
import com.riffle.core.data.IosTokenStorage
import com.riffle.core.data.IosVolumeKeyPreferencesStoreImpl
import com.riffle.core.data.ItemProgressPuller
import com.riffle.core.data.LibraryItemUiProgressSink
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReconcilingItemProgressPuller
import com.riffle.core.data.comic.panel.IosColorPageDecoder
import com.riffle.core.data.comic.panel.IosPageImageDecoder
import com.riffle.core.data.developer.IosDeveloperOptionsRepositoryImpl
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.IosCopyInService
import com.riffle.core.data.localfiles.IosFolderPicker
import com.riffle.core.data.localfiles.IosFolderWalker
import com.riffle.core.data.localfiles.IosLocalFilesFolderRepository
import com.riffle.core.data.localfiles.IosLocalFilesScanner
import com.riffle.core.data.localfiles.IosLocalFilesSourceInstaller
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.BookComicFormattingPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.panel.ColorPageDecoder
import com.riffle.core.domain.comic.panel.InMemoryPanelStore
import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PanelDetectionConfig
import com.riffle.core.domain.comic.panel.PanelEngine
import com.riffle.core.domain.comic.panel.PanelOrchestrator
import com.riffle.core.domain.comic.panel.PanelStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.sync.ProgressRemoteFactory
import com.riffle.core.sync.ReconcileLocks
import org.koin.dsl.module

val iosDataModule = module {
    single<ConnectivityObserver> { IosConnectivityObserver() }
    single<TokenStorage> { IosTokenStorage() }
    single<FileStore> { IosFileStore() }
    single<DeviceLabelResolver> { IosDeviceLabelResolver() }

    single<FolderPickerInterface> { IosFolderPicker() }
    single { IosFolderWalker(get()) }
    single { IosCopyInService(get()) }
    single { IosLocalFilesScanner(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { IosLocalFilesFolderRepository(get(), get(), get(), get()) }
    single<LocalFilesInstallerInterface> { IosLocalFilesSourceInstaller(get(), get(), get()) }
    single<DeviceIdStore> { IosDeviceIdStoreImpl() }
    single<ColorPageDecoder> { IosColorPageDecoder() }
    single<PageImageDecoder> { IosPageImageDecoder() }
    single<PanelStore> { InMemoryPanelStore() }
    single<PanelEngine> { PanelOrchestrator(PanelDetectionConfig(), get(), get<PageImageDecoder>()) }

    // ReadingPositionStoreImpl/AudiobookPositionStoreImpl (core:data commonMain) implement both the
    // reader-facing store interface AND SyncPositionStore, exactly matching Android's wiring — see
    // CoreDataKoinModules.kt. One instance serves both roles (issue #1057/#1065 server-sync wiring).
    single { ReadingPositionStoreImpl(get<ReadingPositionDao>(), get<Clock>()) }
    single<ReadingPositionStore> { get<ReadingPositionStoreImpl>() }
    single<SyncPositionStore<String>> { get<ReadingPositionStoreImpl>() }
    single { AudiobookPositionStoreImpl(get<AudiobookPositionDao>(), get<Clock>()) }
    single<AudiobookPositionStore> { get<AudiobookPositionStoreImpl>() }
    single<SyncPositionStore<Double>> { get<AudiobookPositionStoreImpl>() }
    single<ReadaloudResumeStore> { ReadaloudResumeStoreImpl(get<ReadaloudResumePositionDao>(), get<Clock>()) }

    single { ReconcileLocks() }
    single { WebSourceLibraryItemUpserter(get<LibraryItemDao>()) }
    single {
        LibraryItemUiProgressSink(get<LibraryItemDao>(), get<SourceRepository>(), get<CatalogRegistry>(), get<WebSourceLibraryItemUpserter>())
    }
    single<ProgressRemoteFactory> {
        IosCatalogProgressRemoteFactory(get<CatalogRegistry>(), get<LibraryItemDao>(), get<EbookCfiTranslatorFactory>(), get<Clock>())
    }
    // Reconciles one (sourceId, itemId) against the ABS server: called on reader/player open (pull)
    // and close (push). iOS has no periodic background sweep (issue #1065) — session-open/close is
    // the sync trigger, matching the issue's "no background workers needed" guidance.
    single<ItemProgressPuller> {
        ReconcilingItemProgressPuller(
            get<ReadingPositionStoreImpl>(),
            get<AudiobookPositionStoreImpl>(),
            get<CatalogRegistry>(),
            get<ProgressRemoteFactory>(),
            get<ReconcileLocks>(),
            get(),
            get<LibraryItemUiProgressSink>(),
        )
    }
    single<FormattingPreferencesStore> { IosFormattingPreferencesStoreImpl() }
    single<BookComicFormattingPreferencesStore> { IosBookComicFormattingPreferencesStoreImpl(get<BookComicFormattingPreferencesDao>()) }
    single<ComicFormattingPreferencesStore> { IosComicFormattingPreferencesStoreImpl() }
    single<LibraryMutator> { IosLibraryMutatorImpl(get<LibraryItemDao>(), get<SourceRepository>(), get<Clock>()) }

    // These three are unified with Android via the PreferenceStore seam in commonMain.
    // Fully-qualified to disambiguate from the domain interfaces of the same name.
    single<AppThemeStore> { com.riffle.core.data.AppThemeStore() }
    single<ReadingSpeedStore> { com.riffle.core.data.ReadingSpeedStore() }
    single<WakeLockPreferencesStore> { com.riffle.core.data.WakeLockPreferencesStore() }

    single<CoverGridDensityStore> { IosCoverGridDensityStoreImpl(get<CoverGridScaleDao>()) }
    single<LibraryFilterPreferencesStore> { IosLibraryFilterPreferencesStoreImpl() }
    single<LibraryOrderPreferencesStore> { IosLibraryOrderPreferencesStoreImpl() }
    single<ListeningPreferencesStore> { IosListeningPreferencesStoreImpl() }
    single<VolumeKeyPreferencesStore> { IosVolumeKeyPreferencesStoreImpl() }
    single<ReadaloudPreferencesStore> { IosReadaloudPreferencesStoreImpl() }
    single<DeveloperOptionsRepository> { IosDeveloperOptionsRepositoryImpl() }
}
