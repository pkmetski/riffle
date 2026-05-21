package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.riffle.core.data.CrashReportRepositoryImpl
import com.riffle.core.data.EpubCacheManagerImpl
import com.riffle.core.data.EpubRepositoryImpl
import com.riffle.core.data.BookFormattingPreferencesStoreImpl
import com.riffle.core.data.FormattingPreferencesStoreImpl
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.LibraryRepositoryImpl
import com.riffle.core.data.ReadingPositionStoreImpl
import com.riffle.core.data.ReadingSessionRepositoryImpl
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.ServerDao
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.EpubCacheManager
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
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
        fun provideDatabase(@ApplicationContext context: Context): RiffleDatabase =
            Room.databaseBuilder(context, RiffleDatabase::class.java, "riffle.db")
                .addMigrations(
                    RiffleDatabase.MIGRATION_1_2,
                    RiffleDatabase.MIGRATION_2_3,
                    RiffleDatabase.MIGRATION_3_4,
                    RiffleDatabase.MIGRATION_4_5,
                    RiffleDatabase.MIGRATION_5_6,
                )
                .build()

        @Provides
        @Singleton
        @CrashReportFile
        fun provideCrashReportFile(@ApplicationContext context: Context): File =
            File(context.filesDir, "crash_report.txt")

        @Provides
        @Singleton
        fun provideServerDao(db: RiffleDatabase): ServerDao = db.serverDao()

        @Provides
        @Singleton
        fun provideLibraryDao(db: RiffleDatabase): LibraryDao = db.libraryDao()

        @Provides
        @Singleton
        fun provideLibraryItemDao(db: RiffleDatabase): LibraryItemDao = db.libraryItemDao()

        @Provides
        @Singleton
        fun provideSeriesDao(db: RiffleDatabase): SeriesDao = db.seriesDao()

        @Provides
        @Singleton
        fun provideCollectionDao(db: RiffleDatabase): CollectionDao = db.collectionDao()

        @Provides
        @Singleton
        fun provideReadingPositionDao(db: RiffleDatabase): ReadingPositionDao = db.readingPositionDao()

        @Provides
        @Singleton
        fun provideBookFormattingPreferencesDao(db: RiffleDatabase): BookFormattingPreferencesDao = db.bookFormattingPreferencesDao()

        @Provides
        @Singleton
        fun provideEpubCacheManager(@ApplicationContext context: Context): EpubCacheManager =
            EpubCacheManagerImpl(context.cacheDir.resolve("epubs").also { it.mkdirs() })

        @Provides
        @Singleton
        @FormattingPreferencesDataStore
        fun provideFormattingPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { context.filesDir.resolve("formatting_preferences.preferences_pb") }
        )
    }
}
