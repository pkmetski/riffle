package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.BookFormattingPreferencesStoreImpl
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.EpubCacheManagerImpl
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.FormattingPreferencesStoreImpl
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.LibraryVisibilityPreferencesStoreImpl
import com.riffle.core.data.PdfCacheManagerImpl
import com.riffle.core.data.PdfRepositoryImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.EpubCacheManager
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PdfCacheManager
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsLibraryApi
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
    abstract fun bindEpubRepository(impl: EpubRepositoryImpl): EpubRepository

    @Binds
    @Singleton
    abstract fun bindPdfRepository(impl: PdfRepositoryImpl): PdfRepository

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
        @CrashReportFile
        fun provideCrashReportFile(@ApplicationContext context: Context): File =
            File(context.filesDir, "crash_report.txt")

        @Provides
        @Singleton
        fun provideEpubCacheManager(@ApplicationContext context: Context): EpubCacheManager =
            EpubCacheManagerImpl(context.cacheDir.resolve("epubs").also { it.mkdirs() })

        @Provides
        @Singleton
        fun providePdfCacheManager(@ApplicationContext context: Context): PdfCacheManager =
            PdfCacheManagerImpl(context.cacheDir.resolve("pdfs").also { it.mkdirs() })

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
    }
}
