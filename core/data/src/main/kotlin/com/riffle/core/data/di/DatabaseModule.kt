package com.riffle.core.data.di

import android.content.Context
import androidx.room.Room
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
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
    fun provideBookFormattingPreferencesDao(db: RiffleDatabase): BookFormattingPreferencesDao = db.bookFormattingPreferencesDao()
}
