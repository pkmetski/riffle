package com.riffle.app.di

import android.content.Context
import androidx.room.Room
import com.riffle.core.data.di.DatabaseModule
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.ServerDao
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
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
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

    @Provides
    @Singleton
    fun provideReadaloudLinkDao(db: RiffleDatabase): ReadaloudLinkDao = db.readaloudLinkDao()
}
