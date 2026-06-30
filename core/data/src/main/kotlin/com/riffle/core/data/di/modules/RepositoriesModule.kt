package com.riffle.core.data.di.modules

import com.riffle.core.data.AppUpdateRepositoryImpl
import com.riffle.core.data.ConnectivityObserverImpl
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.CrossEpubIndexBuilderService
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.PdfRepositoryImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.ToReadRepositoryImpl
import com.riffle.core.data.TocRepositoryImpl
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.data.di.PdfCacheStore
import com.riffle.core.data.di.PdfDownloadsStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoriesModule {

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    @Singleton
    abstract fun bindLibraryObserver(impl: LibraryRepositoryImpl): LibraryObserver

    @Binds
    @Singleton
    abstract fun bindLibraryMutator(impl: LibraryRepositoryImpl): LibraryMutator

    @Binds
    @Singleton
    abstract fun bindLibraryRefresher(impl: LibraryRepositoryImpl): LibraryRefresher

    @Binds
    @Singleton
    abstract fun bindToReadRepository(impl: ToReadRepositoryImpl): ToReadRepository

    @Binds
    @Singleton
    abstract fun bindCrashReportRepository(impl: CrashReportRepositoryImpl): CrashReportRepository

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository

    @Binds
    @Singleton
    abstract fun bindCrossEpubIndexBuildTrigger(impl: CrossEpubIndexBuilderService): CrossEpubIndexBuildTrigger

    @Binds
    @Singleton
    abstract fun bindReadingSessionRepository(impl: ReadingSessionRepositoryImpl): ReadingSessionRepository

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(impl: ConnectivityObserverImpl): ConnectivityObserver

    @Binds
    @Singleton
    abstract fun bindTocRepository(impl: TocRepositoryImpl): TocRepository

    companion object {
        @Provides
        @Singleton
        fun provideEpubRepository(
            api: AbsLibraryApi,
            @EpubCacheStore cacheStore: LocalStore,
            @EpubDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): EpubRepository = EpubRepositoryImpl(api, cacheStore, downloadsStore, positionStore, serverRepository, tokenStorage)

        @Provides
        @Singleton
        fun providePdfRepository(
            api: AbsLibraryApi,
            @PdfCacheStore cacheStore: LocalStore,
            @PdfDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): PdfRepository = PdfRepositoryImpl(api, cacheStore, downloadsStore, positionStore, serverRepository, tokenStorage)

        @Provides
        @Singleton
        fun provideLibraryItemOfflineAvailability(
            epubRepository: EpubRepository,
            pdfRepository: PdfRepository,
            audiobookDownloadRepository: AudiobookDownloadRepository,
            bundleAudiobookSource: BundleAudiobookSource,
        ): LibraryItemOfflineAvailability =
            LibraryItemOfflineAvailability(epubRepository, pdfRepository, audiobookDownloadRepository, bundleAudiobookSource)
    }
}
