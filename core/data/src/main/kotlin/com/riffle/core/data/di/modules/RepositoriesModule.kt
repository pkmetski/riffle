package com.riffle.core.data.di.modules

import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.AnnotationsLibraryRepositoryImpl
import com.riffle.core.data.AppUpdateRepositoryImpl
import com.riffle.core.data.ConnectivityObserverImpl
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.LocalToReadStore
import com.riffle.core.data.LocalToReadStoreImpl
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.PlaylistsRepositoryImpl
import com.riffle.core.data.CrossEpubIndexBuilderService
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.PdfRepositoryImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.SourceRepositoryImpl
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
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.catalog.CatalogRegistry
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
    abstract fun bindSourceRepository(impl: SourceRepositoryImpl): SourceRepository

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
    abstract fun bindPlaylistsRepository(impl: PlaylistsRepositoryImpl): PlaylistsRepository

    @Binds
    @Singleton
    abstract fun bindLocalToReadStore(impl: LocalToReadStoreImpl): LocalToReadStore

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

    @Binds
    @Singleton
    abstract fun bindAnnotationsLibraryRepository(impl: AnnotationsLibraryRepositoryImpl): AnnotationsLibraryRepository

    companion object {
        @Provides
        @Singleton
        fun provideEpubRepository(
            catalogRegistry: CatalogRegistry,
            @EpubCacheStore cacheStore: LocalStore,
            @EpubDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            sourceRepository: SourceRepository,
        ): EpubRepository = EpubRepositoryImpl(catalogRegistry, cacheStore, downloadsStore, positionStore, sourceRepository)

        @Provides
        @Singleton
        fun providePdfRepository(
            catalogRegistry: CatalogRegistry,
            @PdfCacheStore cacheStore: LocalStore,
            @PdfDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            sourceRepository: SourceRepository,
        ): PdfRepository = PdfRepositoryImpl(catalogRegistry, cacheStore, downloadsStore, positionStore, sourceRepository)

        @Provides
        @Singleton
        fun provideCbzRepository(
            catalogRegistry: CatalogRegistry,
            @com.riffle.core.data.di.CbzCacheStore cacheStore: LocalStore,
            @com.riffle.core.data.di.CbzDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            sourceRepository: SourceRepository,
        ): com.riffle.core.domain.CbzRepository = com.riffle.core.data.CbzRepositoryImpl(catalogRegistry, cacheStore, downloadsStore, positionStore, sourceRepository)

        @Provides
        @Singleton
        fun provideLibraryItemOfflineAvailability(
            epubRepository: EpubRepository,
            pdfRepository: PdfRepository,
            cbzRepository: com.riffle.core.domain.CbzRepository,
            audiobookDownloadRepository: AudiobookDownloadRepository,
            bundleAudiobookSource: BundleAudiobookSource,
        ): LibraryItemOfflineAvailability =
            LibraryItemOfflineAvailability(epubRepository, pdfRepository, cbzRepository, audiobookDownloadRepository, bundleAudiobookSource)
    }
}
