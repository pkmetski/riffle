package com.riffle.core.data.di

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.Clock
import com.riffle.core.common.FileStore
import com.riffle.core.common.IosRandomProvider
import com.riffle.core.common.RandomProvider
import com.riffle.core.data.AudiobookBookmarkSyncStoreImpl
import com.riffle.core.data.IosAudiobookSleepStopStoreImpl
import com.riffle.core.data.AudiobookPositionStoreImpl
import com.riffle.core.data.CatalogSyncSourceResolver
import com.riffle.core.data.DaoDirtyBookmarkLedger
import com.riffle.core.data.DaoDirtyProgressLedger
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
import com.riffle.core.data.IosReadaloudPreferencesStoreImpl
import com.riffle.core.data.IosTokenStorage
import com.riffle.core.data.IosVolumeKeyPreferencesStoreImpl
import com.riffle.core.data.ItemProgressPuller
import com.riffle.core.data.LibraryItemUiProgressSink
import com.riffle.core.data.ReadaloudResumeStoreImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReconcilingItemProgressPuller
import com.riffle.core.data.comic.panel.IosColorPageDecoder
import com.riffle.core.data.comic.panel.IosPageImageDecoder
import com.riffle.core.data.comic.panel.IosPanelMaskServiceImpl
import com.riffle.core.data.developer.IosDeveloperOptionsRepositoryImpl
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.IosCopyInService
import com.riffle.core.data.localfiles.IosFolderPicker
import com.riffle.core.data.localfiles.IosFolderWalker
import com.riffle.core.data.localfiles.IosLocalFilesFolderRepository
import com.riffle.core.data.localfiles.IosLocalFilesScanner
import com.riffle.core.data.localfiles.IosLocalFilesSourceInstaller
import com.riffle.core.data.localfiles.IosManagedImportsFolder
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.data.localfiles.ManagedImportsFolder
import com.riffle.core.data.localfiles.OpenInImportFeed
import com.riffle.core.data.localfiles.OpenInImporter
import com.riffle.core.data.localfiles.SharedOpenInImporter
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AudiobookBookmarkSyncStore
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
import com.riffle.core.domain.ProgressReconciler
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
import com.riffle.core.domain.comic.panel.PanelMaskService
import com.riffle.core.domain.comic.panel.PanelOrchestrator
import com.riffle.core.domain.comic.panel.PanelStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.sync.AudiobookBookmarkReconciler
import com.riffle.core.sync.BookmarkReconcile
import com.riffle.core.sync.DirtyProgressLedger
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.core.sync.ProgressRemoteFactory
import com.riffle.core.sync.ProgressSweep
import com.riffle.core.sync.ReconcileLocks
import com.riffle.core.sync.RemoteProgressIndex
import com.riffle.core.sync.SyncSourceResolver
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
    // "Open in Riffle": the incoming file is copied into an app-owned folder that is then
    // installed as an ordinary Local Files folder, so the book is scanned, classified and
    // browsable through the machinery a picked folder already uses.
    single<ManagedImportsFolder> { IosManagedImportsFolder(get()) }
    single<OpenInImporter> { SharedOpenInImporter(importsFolder = get(), installer = get()) }
    single { OpenInImportFeed() }
    single<DeviceIdStore> { IosDeviceIdStoreImpl() }
    single<ColorPageDecoder> { IosColorPageDecoder() }
    single<PageImageDecoder> { IosPageImageDecoder() }
    single<PanelStore> { InMemoryPanelStore() }
    single<PanelEngine> { PanelOrchestrator(PanelDetectionConfig(), get(), get<PageImageDecoder>()) }
    single<PanelMaskService> { IosPanelMaskServiceImpl(PanelDetectionConfig(), get<PageImageDecoder>(), get()) }

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
    single<com.riffle.core.domain.AudiobookSleepStopStore> { IosAudiobookSleepStopStoreImpl() }

    single { ReconcileLocks() }
    single { WebSourceLibraryItemUpserter(get<LibraryItemDao>()) }
    single {
        LibraryItemUiProgressSink(get<LibraryItemDao>(), get<SourceRepository>(), get<CatalogRegistry>(), get<WebSourceLibraryItemUpserter>())
    }
    single<ProgressRemoteFactory> {
        IosCatalogProgressRemoteFactory(get<CatalogRegistry>(), get<LibraryItemDao>(), get<EbookCfiTranslatorFactory>(), get<Clock>())
    }
    // Reconciles one (sourceId, itemId) against the ABS server: called on reader/player open (pull)
    // and close (push).
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

    // The durable, book-independent dirty sweep of ADR 0036 — the same ProgressSweep Android
    // binds in CoreDataKoinModules, over the same commonMain ledger/resolver/reconcilers.
    //
    // Until #1071 §14 iOS had *nothing* that retried a failed push: session close was the only
    // trigger, so a position or bookmark saved while offline (or while ABS was down) stayed dirty
    // forever unless the user happened to reopen that exact book while online. Now `ProgressSweep`
    // is real on iOS, driven from three places that stand in for Android's WorkManager jobs —
    // app start and app foreground (`RiffleAppRoot`), and the validated offline→online edge
    // (`kickSweepsOnReconnect`, also shared).
    //
    // Two constructor arguments stay at their defaults, both deliberately:
    //  - `remoteIndex`: CatalogRemoteProgressIndex serves the WebDAV web-source pull (ADR 0063),
    //    which iOS does not have — IosCatalogProgressRemoteFactory has no WebDAV branch either.
    //  - `postSweepMaterializer`: WebSourceLibraryItemMaterializer exists only for those same
    //    WebDAV-synced web sources.
    // Both become relevant the day iOS gets a Kotlin/Native WebDAV client (#1072).
    single<DirtyProgressLedger> { DaoDirtyProgressLedger(get<ReadingPositionDao>(), get<AudiobookPositionDao>()) }
    single<SyncSourceResolver> { CatalogSyncSourceResolver(get<CatalogRegistry>(), get<SourceRepository>()) }
    single<RandomProvider> { IosRandomProvider }
    single<AudiobookBookmarkSyncStore> { AudiobookBookmarkSyncStoreImpl(get<AudiobookBookmarkDao>()) }
    single {
        AudiobookBookmarkReconciler(
            store = get<AudiobookBookmarkSyncStore>(),
            sourceResolver = get<SyncSourceResolver>(),
            clock = get<Clock>(),
            random = get<RandomProvider>(),
        )
    }
    single {
        ProgressSweep(
            ledger = get<DirtyProgressLedger>(),
            sourceResolver = get<SyncSourceResolver>(),
            ebookReconciler = ProgressReconciler(get<ReadingPositionStoreImpl>(), get<LibraryItemUiProgressSink>()),
            audioReconciler = ProgressReconciler(get<AudiobookPositionStoreImpl>(), get<LibraryItemUiProgressSink>()),
            remoteFactory = get<ProgressRemoteFactory>(),
            locks = get<ReconcileLocks>(),
            openTargets = get<OpenReconcileTargets>(),
            bookmarkLedger = DaoDirtyBookmarkLedger(get<AudiobookBookmarkDao>()),
            bookmarkReconcile = BookmarkReconcile { sourceId, itemId ->
                get<AudiobookBookmarkReconciler>().reconcile(sourceId, itemId)
            },
            // Was left at RemoteProgressIndex.EMPTY because the WebDAV enumerator it needs was
            // jvmMain-only (#1072). Without it the sweep only ever visits locally-dirty rows, so
            // a position advanced on another device against a *clean* local row was never pulled
            // back — the exact "clean-row gap" ADR 0063 added the index for.
            remoteIndex = get<RemoteProgressIndex>(),
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
