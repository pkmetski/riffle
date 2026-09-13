package com.riffle.core.data.di

import com.riffle.core.common.Clock
import com.riffle.core.common.FileStore
import com.riffle.core.data.IosAudiobookPositionStoreImpl
import com.riffle.core.data.IosBookComicFormattingPreferencesStoreImpl
import com.riffle.core.data.IosComicFormattingPreferencesStoreImpl
import com.riffle.core.data.IosConnectivityObserver
import com.riffle.core.data.IosDeviceIdStoreImpl
import com.riffle.core.data.IosDeviceLabelResolver
import com.riffle.core.data.IosFileStore
import com.riffle.core.data.IosFormattingPreferencesStoreImpl
import com.riffle.core.data.IosLibraryMutatorImpl
import com.riffle.core.data.IosReadingPositionStoreImpl
import com.riffle.core.data.IosTokenStorage
import com.riffle.core.data.comic.panel.IosColorPageDecoder
import com.riffle.core.data.comic.panel.IosPageImageDecoder
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.IosCopyInService
import com.riffle.core.data.localfiles.IosFolderPicker
import com.riffle.core.data.localfiles.IosFolderWalker
import com.riffle.core.data.localfiles.IosLocalFilesFolderRepository
import com.riffle.core.data.localfiles.IosLocalFilesScanner
import com.riffle.core.data.localfiles.IosLocalFilesSourceInstaller
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.comic.BookComicFormattingPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.panel.ColorPageDecoder
import com.riffle.core.domain.comic.panel.InMemoryPanelStore
import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PanelDetectionConfig
import com.riffle.core.domain.comic.panel.PanelEngine
import com.riffle.core.domain.comic.panel.PanelOrchestrator
import com.riffle.core.domain.comic.panel.PanelStore
import org.koin.dsl.module

val iosDataModule = module {
    single<ConnectivityObserver> { IosConnectivityObserver() }
    single<TokenStorage> { IosTokenStorage() }
    single<FileStore> { IosFileStore() }
    single<DeviceLabelResolver> { IosDeviceLabelResolver() }

    single<FolderPickerInterface> { IosFolderPicker() }
    single { IosFolderWalker(get()) }
    single { IosCopyInService(get()) }
    single { IosLocalFilesScanner(get(), get(), get(), get(), get(), get(), get()) }
    single { IosLocalFilesFolderRepository(get(), get(), get()) }
    single<LocalFilesInstallerInterface> { IosLocalFilesSourceInstaller(get(), get(), get()) }
    single<DeviceIdStore> { IosDeviceIdStoreImpl() }
    single<ColorPageDecoder> { IosColorPageDecoder() }
    single<PageImageDecoder> { IosPageImageDecoder() }
    single<PanelStore> { InMemoryPanelStore() }
    single<PanelEngine> { PanelOrchestrator(PanelDetectionConfig(), get(), get<PageImageDecoder>()) }

    single<ReadingPositionStore> { IosReadingPositionStoreImpl(get<ReadingPositionDao>(), get<Clock>()) }
    single<AudiobookPositionStore> { IosAudiobookPositionStoreImpl(get<AudiobookPositionDao>(), get<Clock>()) }
    single<FormattingPreferencesStore> { IosFormattingPreferencesStoreImpl() }
    single<BookComicFormattingPreferencesStore> { IosBookComicFormattingPreferencesStoreImpl(get<BookComicFormattingPreferencesDao>()) }
    single<ComicFormattingPreferencesStore> { IosComicFormattingPreferencesStoreImpl() }
    single<LibraryMutator> { IosLibraryMutatorImpl(get<LibraryItemDao>(), get<SourceRepository>(), get<Clock>()) }
}
