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
        ReadaloudLinkEntity::class,
    ],
    version = 22,
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
    abstract fun readaloudLinkDao(): ReadaloudLinkDao

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

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `servers` ADD COLUMN `username` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book_formatting_preferences` ADD COLUMN `justifyText` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_formatting_preferences_new` (" +
                        "`itemId` TEXT NOT NULL, " +
                        "`fontSize` REAL, " +
                        "`theme` TEXT, " +
                        "`fontFamily` TEXT, " +
                        "`lineSpacing` REAL, " +
                        "`margins` REAL, " +
                        "`orientation` TEXT, " +
                        "`showChapterMap` INTEGER, " +
                        "`doublePageSpread` INTEGER, " +
                        "`justifyText` INTEGER, " +
                        "PRIMARY KEY(`itemId`))"
                )
                db.execSQL(
                    "INSERT INTO `book_formatting_preferences_new` " +
                        "(itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread, justifyText) " +
                        "SELECT itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread, justifyText " +
                        "FROM `book_formatting_preferences`"
                )
                db.execSQL("DROP TABLE `book_formatting_preferences`")
                db.execSQL("ALTER TABLE `book_formatting_preferences_new` RENAME TO `book_formatting_preferences`")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `book_formatting_preferences` ADD COLUMN `showReadingProgressLabels` INTEGER DEFAULT NULL"
                )
            }
        }

        // Generalises Server beyond Audiobookshelf: a Server is now either ABS or Storyteller
        // (ADR 0020). Existing rows backfill to AUDIOBOOKSHELF — the only Server type that existed
        // before this migration.
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `servers` ADD COLUMN `serverType` TEXT NOT NULL DEFAULT 'AUDIOBOOKSHELF'")
            }
        }

        // Scope reading_positions by serverId so the same itemId can hold distinct positions
        // for different ABS accounts (or different servers). Rows from the previous schema are
        // backfilled to the currently-active server when one exists; if there's no active server
        // we drop the rows — they can't be attributed to any user, and the next sync will refetch.
        // The new FK to servers(id) cascade-deletes positions when a server is removed.
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val activeServerId: String? = db.query("SELECT id FROM servers WHERE isActive = 1 LIMIT 1").use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_positions_new` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`cfi` TEXT NOT NULL, " +
                        "`localUpdatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`serverId`, `itemId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                if (activeServerId != null) {
                    db.execSQL(
                        "INSERT INTO `reading_positions_new` (serverId, itemId, cfi, localUpdatedAt) " +
                            "SELECT ?, itemId, cfi, localUpdatedAt FROM `reading_positions`",
                        arrayOf<Any>(activeServerId),
                    )
                }
                db.execSQL("DROP TABLE `reading_positions`")
                db.execSQL("ALTER TABLE `reading_positions_new` RENAME TO `reading_positions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_positions_serverId` ON `reading_positions` (`serverId`)")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `book_formatting_preferences` ADD COLUMN `showCurrentChapterLabel` INTEGER DEFAULT NULL"
                )
            }
        }

        // Servers are now identified by their ServerType (Audiobookshelf / Storyteller) in the UI,
        // so the URL-host-derived displayName column has no consumers. Drop it.
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `servers_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`isActive` INTEGER NOT NULL, " +
                        "`insecureConnectionAllowed` INTEGER NOT NULL, " +
                        "`username` TEXT NOT NULL, " +
                        "`serverType` TEXT NOT NULL DEFAULT 'AUDIOBOOKSHELF', " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `servers_new` " +
                        "(id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                        "SELECT id, url, isActive, insecureConnectionAllowed, username, serverType " +
                        "FROM `servers`"
                )
                db.execSQL("DROP TABLE `servers`")
                db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
            }
        }

        // Storyteller↔ABS matching (issue #36, ADR 0021). Two coupled changes:
        //
        // 1. `library_items` gains `isbn`/`asin` columns so the matcher can run Tier 1
        //    (exact identifier match) without joining a separate metadata table.
        // 2. New `readaloud_links` table records Confirmed pairings. The ABS-side PK
        //    reflects the natural multiplicity — each ABS item has at most one readaloud,
        //    but a single readaloud can be referenced from many ABS items (the same
        //    conceptual book regularly lives as both an ebook in a Books library and an
        //    audiobook stub in an Audiobooks library). FK-cascade on both server columns
        //    realises "Server removal cascade clears ReadaloudLink rows" from the AC.
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `isbn` TEXT")
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `asin` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `readaloud_links` (" +
                        "`absServerId` TEXT NOT NULL, " +
                        "`absLibraryItemId` TEXT NOT NULL, " +
                        "`storytellerServerId` TEXT NOT NULL, " +
                        "`storytellerBookId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL DEFAULT 'CONFIRMED', " +
                        "`userConfirmed` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`absServerId`, `absLibraryItemId`), " +
                        "FOREIGN KEY(`storytellerServerId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`absServerId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_readaloud_links_storytellerServerId_storytellerBookId` " +
                        "ON `readaloud_links` (`storytellerServerId`, `storytellerBookId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_readaloud_links_storytellerServerId` " +
                        "ON `readaloud_links` (`storytellerServerId`)"
                )
            }
        }
    }
}
