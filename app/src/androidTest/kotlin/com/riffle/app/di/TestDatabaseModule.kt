package com.riffle.app.di

import android.content.Context
import com.riffle.core.data.di.DatabaseModule
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CrossEpubIndexDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.PlaylistDao
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.RemoteItemFreshnessDao
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SourceDao
import com.riffle.core.database.TocCacheDao
import com.riffle.core.database.openRiffleDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): RiffleDatabaseAccess =
        openRiffleDatabase(
            context = context,
            fallbackToDestructiveMigration = true,
        )

    @Provides
    @Singleton
    fun provideSourceDao(db: RiffleDatabaseAccess): SourceDao = db.sourceDao()

    @Provides
    @Singleton
    fun provideLibraryDao(db: RiffleDatabaseAccess): LibraryDao = db.libraryDao()

    @Provides
    @Singleton
    fun provideLibraryItemDao(db: RiffleDatabaseAccess): LibraryItemDao = db.libraryItemDao()

    @Provides
    @Singleton
    fun provideLocalFilesFolderDao(db: RiffleDatabaseAccess): LocalFilesFolderDao = db.localFilesFolderDao()

    @Provides
    @Singleton
    fun provideLocalFileMetadataOverrideDao(db: RiffleDatabaseAccess): LocalFileMetadataOverrideDao =
        db.localFileMetadataOverrideDao()

    @Provides
    @Singleton
    fun provideLocalFilesFileDao(db: RiffleDatabaseAccess): LocalFilesFileDao = db.localFilesFileDao()

    @Provides
    @Singleton
    fun provideLocalFilesFileFolderDao(db: RiffleDatabaseAccess): LocalFilesFileFolderDao =
        db.localFilesFileFolderDao()

    @Provides
    @Singleton
    fun provideSeriesDao(db: RiffleDatabaseAccess): SeriesDao = db.seriesDao()

    @Provides
    @Singleton
    fun provideCollectionDao(db: RiffleDatabaseAccess): CollectionDao = db.collectionDao()

    @Provides
    @Singleton
    fun provideReadingPositionDao(db: RiffleDatabaseAccess): ReadingPositionDao = db.readingPositionDao()

    @Provides
    @Singleton
    fun provideReadaloudResumePositionDao(db: RiffleDatabaseAccess): ReadaloudResumePositionDao = db.readaloudResumePositionDao()

    @Provides
    @Singleton
    fun provideBookFormattingPreferencesDao(db: RiffleDatabaseAccess): BookFormattingPreferencesDao = db.bookFormattingPreferencesDao()

    @Provides
    @Singleton
    fun provideAudioPlaybackPreferencesDao(db: RiffleDatabaseAccess): AudioPlaybackPreferencesDao = db.audioPlaybackPreferencesDao()

    @Provides
    @Singleton
    fun provideAudiobookPositionDao(db: RiffleDatabaseAccess): AudiobookPositionDao = db.audiobookPositionDao()

    @Provides
    @Singleton
    fun provideAudiobookBookmarkDao(db: RiffleDatabaseAccess): AudiobookBookmarkDao = db.audiobookBookmarkDao()

    @Provides
    @Singleton
    fun provideReadaloudLinkDao(db: RiffleDatabaseAccess): ReadaloudLinkDao = db.readaloudLinkDao()

    @Provides
    @Singleton
    fun provideReadaloudCandidateDao(db: RiffleDatabaseAccess): ReadaloudCandidateDao = db.readaloudCandidateDao()

    @Provides
    @Singleton
    fun provideReadaloudDismissalDao(db: RiffleDatabaseAccess): ReadaloudDismissalDao = db.readaloudDismissalDao()

    @Provides
    @Singleton
    fun provideCrossEpubIndexDao(db: RiffleDatabaseAccess): CrossEpubIndexDao = db.crossEpubIndexDao()

    @Provides
    @Singleton
    fun provideAnnotationDao(db: RiffleDatabaseAccess): AnnotationDao = db.annotationDao()

    @Provides
    @Singleton
    fun provideTocCacheDao(db: RiffleDatabaseAccess): TocCacheDao = db.tocCacheDao()

    @Provides
    @Singleton
    fun providePublicationMetricsCacheDao(db: RiffleDatabaseAccess): PublicationMetricsCacheDao =
        db.publicationMetricsCacheDao()

    @Provides
    @Singleton
    fun provideAudiobookChapterCacheDao(db: RiffleDatabaseAccess): AudiobookChapterCacheDao = db.audiobookChapterCacheDao()

    @Provides
    @Singleton
    fun provideRemoteItemFreshnessDao(db: RiffleDatabaseAccess): RemoteItemFreshnessDao =
        db.remoteItemFreshnessDao()

    @Provides
    @Singleton
    fun providePlaylistDao(db: RiffleDatabaseAccess): PlaylistDao = db.playlistDao()

    @Provides
    @Singleton
    fun provideBookComicFormattingPreferencesDao(db: RiffleDatabaseAccess): BookComicFormattingPreferencesDao =
        db.bookComicFormattingPreferencesDao()

    @Provides
    @Singleton
    fun provideDictionaryPackDao(db: RiffleDatabaseAccess): com.riffle.core.database.DictionaryPackDao =
        db.dictionaryPackDao()

    @Provides
    @Singleton
    fun provideLookupHistoryDao(db: RiffleDatabaseAccess): com.riffle.core.database.LookupHistoryDao =
        db.lookupHistoryDao()

    @Provides
    @Singleton
    fun provideCoverGridScaleDao(db: RiffleDatabaseAccess): CoverGridScaleDao = db.coverGridScaleDao()
}
