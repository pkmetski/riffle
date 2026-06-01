package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.BookFormattingPreferencesStoreImpl
import com.riffle.core.data.ConnectivityObserverImpl
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.DownloadsRepositoryImpl
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.FormattingPreferencesStoreImpl
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.LibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.LocalStoreImpl
import com.riffle.core.data.PdfRepositoryImpl
import com.riffle.core.data.ReadaloudLinkRepositoryImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.ToReadRepositoryImpl
import com.riffle.core.data.VolumeKeyPreferencesStoreImpl
import com.riffle.core.data.WakeLockPreferencesStoreImpl
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.data.EpubBundleFetcher
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.StorytellerApi
import com.riffle.core.network.StorytellerApiClient
import com.riffle.core.network.StorytellerBundleApiImpl
import com.riffle.core.network.StorytellerLibraryApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashReportFile

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FormattingPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryVisibilityPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WakeLockPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VolumeKeyPreferencesDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubCacheStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubDownloadsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfCacheStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfDownloadsStore

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindToReadRepository(impl: ToReadRepositoryImpl): ToReadRepository

    @Binds
    @Singleton
    abstract fun bindReadaloudLinkRepository(impl: ReadaloudLinkRepositoryImpl): ReadaloudLinkRepository

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: KeystoreTokenStorage): TokenStorage

    @Binds
    @Singleton
    abstract fun bindCrashReportRepository(impl: CrashReportRepositoryImpl): CrashReportRepository

    @Binds
    @Singleton
    abstract fun bindAbsApi(impl: AbsApiClient): AbsApi

    @Binds
    @Singleton
    abstract fun bindAbsLibraryApi(impl: AbsApiClient): AbsLibraryApi

    @Binds
    @Singleton
    abstract fun bindAbsSessionApi(impl: AbsApiClient): AbsSessionApi

    @Binds
    @Singleton
    abstract fun bindAbsServerInfoApi(impl: AbsApiClient): AbsServerInfoApi

    @Binds
    @Singleton
    abstract fun bindStorytellerApi(impl: StorytellerApiClient): StorytellerApi

    @Binds
    @Singleton
    abstract fun bindStorytellerLibraryApi(impl: StorytellerApiClient): StorytellerLibraryApi

    @Binds
    @Singleton
    abstract fun bindReadingPositionStore(impl: ReadingPositionStoreImpl): ReadingPositionStore

    @Binds
    @Singleton
    abstract fun bindReadingSessionRepository(impl: ReadingSessionRepositoryImpl): ReadingSessionRepository

    @Binds
    @Singleton
    abstract fun bindFormattingPreferencesStore(impl: FormattingPreferencesStoreImpl): FormattingPreferencesStore

    @Binds
    @Singleton
    abstract fun bindBookFormattingPreferencesStore(impl: BookFormattingPreferencesStoreImpl): BookFormattingPreferencesStore

    @Binds
    @Singleton
    abstract fun bindLibraryVisibilityPreferencesStore(impl: LibraryVisibilityPreferencesStoreImpl): LibraryVisibilityPreferencesStore

    @Binds
    @Singleton
    abstract fun bindWakeLockPreferencesStore(impl: WakeLockPreferencesStoreImpl): WakeLockPreferencesStore

    @Binds
    @Singleton
    abstract fun bindVolumeKeyPreferencesStore(impl: VolumeKeyPreferencesStoreImpl): VolumeKeyPreferencesStore

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(impl: ConnectivityObserverImpl): ConnectivityObserver

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideAbsApiClient(okHttpClient: OkHttpClient): AbsApiClient =
            AbsApiClient(okHttpClient)

        @Provides
        @Singleton
        fun provideStorytellerApiClient(okHttpClient: OkHttpClient): StorytellerApiClient =
            StorytellerApiClient(okHttpClient)

        @Provides
        @Singleton
        @CrashReportFile
        fun provideCrashReportFile(@ApplicationContext context: Context): File =
            File(context.filesDir, "crash_report.txt")

        @Provides
        @Singleton
        @EpubCacheStore
        fun provideEpubCacheStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.cacheDir.resolve("epubs").also { it.mkdirs() }, ".epub")

        @Provides
        @Singleton
        @EpubDownloadsStore
        fun provideEpubDownloadsStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.filesDir.resolve("downloads/epubs").also { it.mkdirs() }, ".epub")

        @Provides
        @Singleton
        @PdfCacheStore
        fun providePdfCacheStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.cacheDir.resolve("pdfs").also { it.mkdirs() }, ".pdf")

        @Provides
        @Singleton
        @PdfDownloadsStore
        fun providePdfDownloadsStore(@ApplicationContext context: Context): LocalStore =
            LocalStoreImpl(context.filesDir.resolve("downloads/pdfs").also { it.mkdirs() }, ".pdf")

        @Provides
        @Singleton
        fun provideStorytellerBundleApiImpl(okHttpClient: OkHttpClient): StorytellerBundleApiImpl =
            StorytellerBundleApiImpl(okHttpClient)

        @Provides
        @Singleton
        fun provideEpubBundleFetcher(
            @ApplicationContext context: Context,
            bundleApi: StorytellerBundleApiImpl,
        ): EpubBundleFetcher = EpubBundleFetcher(
            api = bundleApi,
            workingDirProvider = { context.cacheDir.resolve("epub-bundles").also { it.mkdirs() } },
        )

        @Provides
        @Singleton
        fun provideEpubRepository(
            api: AbsLibraryApi,
            bundleFetcher: EpubBundleFetcher,
            bundleProbe: StorytellerBundleApiImpl,
            @EpubCacheStore cacheStore: LocalStore,
            @EpubDownloadsStore downloadsStore: LocalStore,
            positionStore: ReadingPositionStore,
            serverRepository: ServerRepository,
            tokenStorage: TokenStorage,
        ): EpubRepository = EpubRepositoryImpl(api, bundleFetcher, bundleProbe, cacheStore, downloadsStore, positionStore, serverRepository, tokenStorage)

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
        fun provideDownloadsRepository(
            @EpubCacheStore epubCacheStore: LocalStore,
            @EpubDownloadsStore epubDownloadsStore: LocalStore,
            @PdfCacheStore pdfCacheStore: LocalStore,
            @PdfDownloadsStore pdfDownloadsStore: LocalStore,
        ): DownloadsRepository = DownloadsRepositoryImpl(epubCacheStore, epubDownloadsStore, pdfCacheStore, pdfDownloadsStore)

        @Provides
        @Singleton
        @FormattingPreferencesDataStore
        fun provideFormattingPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.formattingPreferencesDataStore

        @Provides
        @Singleton
        @LibraryVisibilityPreferencesDataStore
        fun provideLibraryVisibilityPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.libraryVisibilityPreferencesDataStore

        @Provides
        @Singleton
        @WakeLockPreferencesDataStore
        fun provideWakeLockPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.wakeLockPreferencesDataStore

        @Provides
        @Singleton
        @VolumeKeyPreferencesDataStore
        fun provideVolumeKeyPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.volumeKeyPreferencesDataStore
    }
}
