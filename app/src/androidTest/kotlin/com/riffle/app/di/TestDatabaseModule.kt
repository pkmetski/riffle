package com.riffle.app.di

import android.content.Context
import androidx.room.Room
import com.riffle.core.data.di.DatabaseModule
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CrossEpubIndexDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.RemoteItemFreshnessDao
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SourceDao
import com.riffle.core.database.TocCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {

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
                RiffleDatabase.MIGRATION_6_7,
                RiffleDatabase.MIGRATION_42_43,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideSourceDao(db: RiffleDatabase): SourceDao = db.sourceDao()

    @Provides
    @Singleton
    fun provideLibraryDao(db: RiffleDatabase): LibraryDao = db.libraryDao()

    @Provides
    @Singleton
    fun provideLibraryItemDao(db: RiffleDatabase): LibraryItemDao = db.libraryItemDao()

    @Provides
    @Singleton
    fun provideLocalFilesFolderDao(db: RiffleDatabase): LocalFilesFolderDao = db.localFilesFolderDao()

    @Provides
    @Singleton
    fun provideLocalFilesFileDao(db: RiffleDatabase): LocalFilesFileDao = db.localFilesFileDao()

    @Provides
    @Singleton
    fun provideLocalFilesFileFolderDao(db: RiffleDatabase): LocalFilesFileFolderDao =
        db.localFilesFileFolderDao()

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
    fun provideReadaloudResumePositionDao(db: RiffleDatabase): ReadaloudResumePositionDao = db.readaloudResumePositionDao()

    @Provides
    @Singleton
    fun provideBookFormattingPreferencesDao(db: RiffleDatabase): BookFormattingPreferencesDao = db.bookFormattingPreferencesDao()

    @Provides
    @Singleton
    fun provideAudioPlaybackPreferencesDao(db: RiffleDatabase): AudioPlaybackPreferencesDao = db.audioPlaybackPreferencesDao()

    @Provides
    @Singleton
    fun provideAudiobookPositionDao(db: RiffleDatabase): AudiobookPositionDao = db.audiobookPositionDao()

    @Provides
    @Singleton
    fun provideAudiobookBookmarkDao(db: RiffleDatabase): AudiobookBookmarkDao = db.audiobookBookmarkDao()

    @Provides
    @Singleton
    fun provideReadaloudLinkDao(db: RiffleDatabase): ReadaloudLinkDao = db.readaloudLinkDao()

    @Provides
    @Singleton
    fun provideReadaloudCandidateDao(db: RiffleDatabase): ReadaloudCandidateDao = db.readaloudCandidateDao()

    @Provides
    @Singleton
    fun provideReadaloudDismissalDao(db: RiffleDatabase): ReadaloudDismissalDao = db.readaloudDismissalDao()

    @Provides
    @Singleton
    fun provideCrossEpubIndexDao(db: RiffleDatabase): CrossEpubIndexDao = db.crossEpubIndexDao()

    @Provides
    @Singleton
    fun provideAnnotationDao(db: RiffleDatabase): AnnotationDao = db.annotationDao()

    @Provides
    @Singleton
    fun provideTocCacheDao(db: RiffleDatabase): TocCacheDao = db.tocCacheDao()

    @Provides
    @Singleton
    fun provideAudiobookChapterCacheDao(db: RiffleDatabase): AudiobookChapterCacheDao = db.audiobookChapterCacheDao()

    @Provides
    @Singleton
    fun provideRemoteItemFreshnessDao(db: RiffleDatabase): RemoteItemFreshnessDao =
        db.remoteItemFreshnessDao()
}
