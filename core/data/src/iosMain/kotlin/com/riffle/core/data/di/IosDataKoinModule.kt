package com.riffle.core.data.di

import com.riffle.core.common.FileStore
import com.riffle.core.data.IosConnectivityObserver
import com.riffle.core.data.IosDeviceIdStoreImpl
import com.riffle.core.data.IosDeviceLabelResolver
import com.riffle.core.data.IosFileStore
import com.riffle.core.data.IosTokenStorage
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.IosCopyInService
import com.riffle.core.data.localfiles.IosFolderPicker
import com.riffle.core.data.localfiles.IosFolderWalker
import com.riffle.core.data.localfiles.IosLocalFilesFolderRepository
import com.riffle.core.data.localfiles.IosLocalFilesScanner
import com.riffle.core.data.localfiles.IosLocalFilesSourceInstaller
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.TokenStorage
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
}
