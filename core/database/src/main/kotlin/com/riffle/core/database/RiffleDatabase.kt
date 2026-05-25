package com.riffle.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ServerEntity::class,
        LibraryEntity::class,
        LibraryItemEntity::class,
        SeriesEntity::class,
        CollectionEntity::class,
        SeriesItemEntity::class,
        CollectionItemEntity::class,
        ReadingPositionEntity::class,
        BookFormattingPreferencesEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class RiffleDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun libraryDao(): LibraryDao
    abstract fun libraryItemDao(): LibraryItemDao
    abstract fun seriesDao(): SeriesDao
    abstract fun collectionDao(): CollectionDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun bookFormattingPreferencesDao(): BookFormattingPreferencesDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `libraries` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `serverId` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_items` (`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `coverUrl` TEXT, `readingProgress` REAL NOT NULL, `isDownloaded` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `libraries` ADD COLUMN `isUnsupported` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `isSupported` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `ebookFileIno` TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reading_positions` (`itemId` TEXT NOT NULL, `cfi` TEXT NOT NULL, PRIMARY KEY(`itemId`))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_formatting_preferences` (`itemId` TEXT NOT NULL, `fontSize` REAL NOT NULL, `theme` TEXT NOT NULL, `fontFamily` TEXT NOT NULL, `lineSpacing` REAL NOT NULL, `margins` REAL NOT NULL, `orientation` TEXT NOT NULL, PRIMARY KEY(`itemId`))"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `ebookFormat` TEXT NOT NULL DEFAULT 'epub'")
                db.execSQL(
                    "ALTER TABLE `book_formatting_preferences` ADD COLUMN `showChapterMap` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series` (`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `name` TEXT NOT NULL, `coverUrl` TEXT, `bookCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `name` TEXT NOT NULL, `bookCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_items` (`seriesId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `sequenceOrder` REAL NOT NULL, PRIMARY KEY(`seriesId`, `itemId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_items` (`collectionId` TEXT NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`collectionId`, `itemId`))"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `description` TEXT")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `seriesName` TEXT")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `publishedYear` TEXT")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `genres` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `publisher` TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `lastOpenedAt` INTEGER")
                db.execSQL("ALTER TABLE `reading_positions` ADD COLUMN `localUpdatedAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_items_new` (" +
                        "`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, `coverUrl` TEXT, `readingProgress` REAL NOT NULL, " +
                        "`isSupported` INTEGER NOT NULL, `ebookFileIno` TEXT, `ebookFormat` TEXT NOT NULL, " +
                        "`description` TEXT, `seriesName` TEXT, `publishedYear` TEXT, " +
                        "`genres` TEXT NOT NULL, `publisher` TEXT, `lastOpenedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `library_items_new` " +
                        "(id, libraryId, title, author, coverUrl, readingProgress, isSupported, " +
                        "ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt) " +
                        "SELECT id, libraryId, title, author, coverUrl, readingProgress, isSupported, " +
                        "ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt " +
                        "FROM `library_items`"
                )
                db.execSQL("DROP TABLE `library_items`")
                db.execSQL("ALTER TABLE `library_items_new` RENAME TO `library_items`")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_items_new` (" +
                        "`id` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, `coverUrl` TEXT, `readingProgress` REAL NOT NULL, " +
                        "`ebookFileIno` TEXT, `ebookFormat` TEXT NOT NULL, " +
                        "`description` TEXT, `seriesName` TEXT, `publishedYear` TEXT, " +
                        "`genres` TEXT NOT NULL, `publisher` TEXT, `lastOpenedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `library_items_new` " +
                        "(id, libraryId, title, author, coverUrl, readingProgress, " +
                        "ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt) " +
                        "SELECT id, libraryId, title, author, coverUrl, readingProgress, " +
                        "ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt " +
                        "FROM `library_items`"
                )
                db.execSQL("DROP TABLE `library_items`")
                db.execSQL("ALTER TABLE `library_items_new` RENAME TO `library_items`")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `addedAt` INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book_formatting_preferences` ADD COLUMN `doublePageSpread` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
