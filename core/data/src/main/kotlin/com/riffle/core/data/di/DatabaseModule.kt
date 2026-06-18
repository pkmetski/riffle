package com.riffle.core.data.di

import android.content.Context
import androidx.room.Room
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AudioPlaybackPreferencesDao
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.CrossEpubIndexDao
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.ServerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

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
                RiffleDatabase.MIGRATION_7_8,
                RiffleDatabase.MIGRATION_8_9,
                RiffleDatabase.MIGRATION_9_10,
                RiffleDatabase.MIGRATION_10_11,
                RiffleDatabase.MIGRATION_11_12,
                RiffleDatabase.MIGRATION_12_13,
                RiffleDatabase.MIGRATION_13_14,
                RiffleDatabase.MIGRATION_14_15,
                RiffleDatabase.MIGRATION_15_16,
                RiffleDatabase.MIGRATION_16_17,
                RiffleDatabase.MIGRATION_17_18,
                RiffleDatabase.MIGRATION_18_19,
                RiffleDatabase.MIGRATION_19_20,
                RiffleDatabase.MIGRATION_20_21,
                RiffleDatabase.MIGRATION_21_22,
                RiffleDatabase.MIGRATION_22_23,
                RiffleDatabase.MIGRATION_23_24,
                RiffleDatabase.MIGRATION_24_25,
                RiffleDatabase.MIGRATION_25_26,
                RiffleDatabase.MIGRATION_26_27,
                RiffleDatabase.MIGRATION_27_28,
                RiffleDatabase.MIGRATION_28_29,
                RiffleDatabase.MIGRATION_29_30,
                RiffleDatabase.MIGRATION_30_31,
                RiffleDatabase.MIGRATION_31_32,
                RiffleDatabase.MIGRATION_32_33,
                RiffleDatabase.MIGRATION_33_34,
                RiffleDatabase.MIGRATION_34_35,
                RiffleDatabase.MIGRATION_35_36,
                RiffleDatabase.MIGRATION_36_37,
            )
            .build()

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
    fun provideAudiobookBookmarkDao(db: RiffleDatabase): com.riffle.core.database.AudiobookBookmarkDao =
        db.audiobookBookmarkDao()

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
}
