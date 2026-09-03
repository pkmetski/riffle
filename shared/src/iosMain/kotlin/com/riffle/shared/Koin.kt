package com.riffle.shared

import com.riffle.core.data.IosLastOpenedLibraryStoreImpl
import com.riffle.core.data.IosLibraryObserverImpl
import com.riffle.core.data.IosLibraryRefresherImpl
import com.riffle.core.data.IosLibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.IosSourceRepositoryImpl
import com.riffle.core.data.di.iosDataModule
import com.riffle.core.data.di.iosDatabaseModule
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.logging.iosLoggingModule
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.KomgaLibraryApiClient
import com.riffle.core.network.createDefaultHttpClient
import com.riffle.feature.library.HomeViewModel
import org.koin.dsl.module
import org.koin.core.context.startKoin as koinStartKoin

private val iosLibraryModule = module {
    single { createDefaultHttpClient() }
    single { AbsApiClient(get()) }
    single<AbsApi> { get<AbsApiClient>() }
    single<AbsLibraryApi> { get<AbsApiClient>() }
    single<KomgaLibraryApi> { KomgaLibraryApiClient(get()) }

    single<DispatcherProvider> { IosDispatcherProvider }
    single<SourceRepository> { IosSourceRepositoryImpl(get(), get(), get()) }
    single<LibraryObserver> { IosLibraryObserverImpl(get(), get()) }
    single<LibraryRefresher> { IosLibraryRefresherImpl(get(), get(), get(), get(), get()) }
    single<LastOpenedLibraryStore> { IosLastOpenedLibraryStoreImpl() }
    single<LibraryVisibilityPreferencesStore> { IosLibraryVisibilityPreferencesStoreImpl() }
    single { RefreshLibraries(get()) }
    single { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    single { AddAbsSourceViewModel(get(), get(), get()) }
}

fun startKoin() {
    koinStartKoin {
        modules(
            iosLoggingModule,
            iosDataModule,
            iosDatabaseModule,
            iosLibraryModule,
        )
    }
}
