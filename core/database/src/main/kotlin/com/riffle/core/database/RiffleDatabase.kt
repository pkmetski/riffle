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
        ReadaloudCandidateEntity::class,
        ReadaloudDismissalEntity::class,
        CrossEpubIndexEntity::class,
        AnnotationEntity::class,
        ReadaloudResumePositionEntity::class,
        AudioPlaybackPreferencesEntity::class,
        AudiobookPositionEntity::class,
        AudiobookBookmarkEntity::class,
    ],
    version = 37,
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
    abstract fun readaloudCandidateDao(): ReadaloudCandidateDao
    abstract fun readaloudDismissalDao(): ReadaloudDismissalDao
    abstract fun crossEpubIndexDao(): CrossEpubIndexDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun readaloudResumePositionDao(): ReadaloudResumePositionDao
    abstract fun audioPlaybackPreferencesDao(): AudioPlaybackPreferencesDao
    abstract fun audiobookPositionDao(): AudiobookPositionDao
    abstract fun audiobookBookmarkDao(): AudiobookBookmarkDao

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

        // Completes the ADR 0021 match ladder (issue #39). Two new tables back the review queue:
        //
        // 1. `readaloud_candidates` records Tier 3 fuzzy candidates awaiting user review — one
        //    row per (readaloud, ABS item) pair with the matcher's similarity score. Both
        //    server columns FK-cascade so candidates clear when either Server is removed.
        // 2. `readaloud_dismissals` records sticky user decisions: a per-book "don't ask again"
        //    (BOOK scope, empty ABS ids) and per-candidate dismissals (CANDIDATE scope). Only
        //    the Storyteller server FK-cascades; the ABS id is a sentinel for book scope.
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `readaloud_candidates` (" +
                        "`storytellerServerId` TEXT NOT NULL, " +
                        "`storytellerBookId` TEXT NOT NULL, " +
                        "`absServerId` TEXT NOT NULL, " +
                        "`absLibraryItemId` TEXT NOT NULL, " +
                        "`score` REAL NOT NULL, " +
                        "PRIMARY KEY(`storytellerServerId`, `storytellerBookId`, `absServerId`, `absLibraryItemId`), " +
                        "FOREIGN KEY(`storytellerServerId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`absServerId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_readaloud_candidates_absServerId` " +
                        "ON `readaloud_candidates` (`absServerId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `readaloud_dismissals` (" +
                        "`storytellerServerId` TEXT NOT NULL, " +
                        "`storytellerBookId` TEXT NOT NULL, " +
                        "`scope` TEXT NOT NULL, " +
                        "`absServerId` TEXT NOT NULL, " +
                        "`absLibraryItemId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`storytellerServerId`, `storytellerBookId`, `absServerId`, `absLibraryItemId`), " +
                        "FOREIGN KEY(`storytellerServerId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
            }
        }

        // Cross-EPUB character-position index cache (issue #38, ADR 0019). A standalone
        // local cache table keyed by the two source EPUBs' checksums — no foreign keys,
        // since a row is invalidated by a checksum change (keyed-lookup miss), not by any
        // Server lifecycle event.
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cross_epub_index` (" +
                        "`absEpubChecksum` TEXT NOT NULL, " +
                        "`storytellerEpubChecksum` TEXT NOT NULL, " +
                        "`perChapterMapsBlob` TEXT NOT NULL, " +
                        "`builtAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`absEpubChecksum`, `storytellerEpubChecksum`))"
                )
            }
        }

        // 24 → 25: the sync-ready Annotations store (ADR 0024 / 0025). One table carries every
        // field the future W3C format + per-device-file merge needs — stable UUID identity,
        // origin/last-modified device ids, a `deleted` tombstone, the CFI range anchor, colour
        // token, nullable note, and the snippet + chapter href fallback. Scoped to the ABS
        // Library Item (serverId + itemId); FK-cascade on serverId clears rows when the server
        // is removed. v1 writes locally only — no sync target reads any of it yet.
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `annotations` (" +
                        "`id` TEXT NOT NULL, " +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`cfi` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL, " +
                        "`note` TEXT, " +
                        "`textSnippet` TEXT NOT NULL, " +
                        "`chapterHref` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`originDeviceId` TEXT NOT NULL, " +
                        "`lastModifiedByDeviceId` TEXT NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotations_serverId_itemId` " +
                        "ON `annotations` (`serverId`, `itemId`)"
                )
            }
        }

        // 25 → 26: key Library Items by (serverId, itemId) end-to-end (issue #81, ADR 0025).
        // Item ids are unique only within a Server — two Storyteller Servers each emit "1", "2", …
        // — so itemId-alone keying collides. Recreate `library_items` with a `serverId` column and
        // a composite PK (serverId, id), backfilling serverId from each item's owning library
        // (libraryId → libraries.serverId); an FK to servers cascade-deletes a Server's items.
        // The series_items / collection_items join tables and book_formatting_preferences gain
        // `serverId` in their PKs, backfilled by resolving itemId → the recreated library_items.
        // Rows whose itemId no longer resolves to a surviving item (hence Server) are dropped as
        // orphans. `reading_positions`, `readaloud_*`, and `annotations` are already (serverId,
        // itemId)-keyed and untouched.
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // library_items: add serverId + composite PK, backfilled from the owning library.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_items_new` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`id` TEXT NOT NULL, " +
                        "`libraryId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, " +
                        "`coverUrl` TEXT, " +
                        "`readingProgress` REAL NOT NULL, " +
                        "`ebookFileIno` TEXT, " +
                        "`ebookFormat` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`seriesName` TEXT, " +
                        "`publishedYear` TEXT, " +
                        "`genres` TEXT NOT NULL, " +
                        "`publisher` TEXT, " +
                        "`lastOpenedAt` INTEGER, " +
                        "`addedAt` INTEGER, " +
                        "`isbn` TEXT, " +
                        "`asin` TEXT, " +
                        "PRIMARY KEY(`serverId`, `id`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `library_items_new` " +
                        "(serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFileIno, " +
                        "ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt, addedAt, isbn, asin) " +
                        "SELECT lib.serverId, li.id, li.libraryId, li.title, li.author, li.coverUrl, li.readingProgress, " +
                        "li.ebookFileIno, li.ebookFormat, li.description, li.seriesName, li.publishedYear, li.genres, " +
                        "li.publisher, li.lastOpenedAt, li.addedAt, li.isbn, li.asin " +
                        "FROM `library_items` li JOIN `libraries` lib ON li.libraryId = lib.id"
                )
                db.execSQL("DROP TABLE `library_items`")
                db.execSQL("ALTER TABLE `library_items_new` RENAME TO `library_items`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_serverId` ON `library_items` (`serverId`)")

                // series_items: add serverId to the PK, resolved via the item's owning server.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_items_new` (" +
                        "`seriesId` TEXT NOT NULL, " +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`sequenceOrder` REAL NOT NULL, " +
                        "PRIMARY KEY(`seriesId`, `serverId`, `itemId`))"
                )
                db.execSQL(
                    "INSERT INTO `series_items_new` (seriesId, serverId, itemId, sequenceOrder) " +
                        "SELECT si.seriesId, li.serverId, si.itemId, si.sequenceOrder " +
                        "FROM `series_items` si JOIN `library_items` li ON si.itemId = li.id"
                )
                db.execSQL("DROP TABLE `series_items`")
                db.execSQL("ALTER TABLE `series_items_new` RENAME TO `series_items`")

                // collection_items: same treatment.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_items_new` (" +
                        "`collectionId` TEXT NOT NULL, " +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `serverId`, `itemId`))"
                )
                db.execSQL(
                    "INSERT INTO `collection_items_new` (collectionId, serverId, itemId) " +
                        "SELECT ci.collectionId, li.serverId, ci.itemId " +
                        "FROM `collection_items` ci JOIN `library_items` li ON ci.itemId = li.id"
                )
                db.execSQL("DROP TABLE `collection_items`")
                db.execSQL("ALTER TABLE `collection_items_new` RENAME TO `collection_items`")

                // book_formatting_preferences: add serverId + composite PK for identity (still
                // per-device, never synced). Backfill via the item's owning server; orphans drop.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_formatting_preferences_new` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`fontSize` REAL, " +
                        "`theme` TEXT, " +
                        "`fontFamily` TEXT, " +
                        "`lineSpacing` REAL, " +
                        "`margins` REAL, " +
                        "`orientation` TEXT, " +
                        "`showChapterMap` INTEGER, " +
                        "`showReadingProgressLabels` INTEGER, " +
                        "`showCurrentChapterLabel` INTEGER, " +
                        "`doublePageSpread` INTEGER, " +
                        "`justifyText` INTEGER, " +
                        "PRIMARY KEY(`serverId`, `itemId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `book_formatting_preferences_new` " +
                        "(serverId, itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, " +
                        "showChapterMap, showReadingProgressLabels, showCurrentChapterLabel, doublePageSpread, justifyText) " +
                        "SELECT li.serverId, bfp.itemId, bfp.fontSize, bfp.theme, bfp.fontFamily, bfp.lineSpacing, " +
                        "bfp.margins, bfp.orientation, bfp.showChapterMap, bfp.showReadingProgressLabels, " +
                        "bfp.showCurrentChapterLabel, bfp.doublePageSpread, bfp.justifyText " +
                        "FROM `book_formatting_preferences` bfp JOIN `library_items` li ON bfp.itemId = li.id"
                )
                db.execSQL("DROP TABLE `book_formatting_preferences`")
                db.execSQL("ALTER TABLE `book_formatting_preferences_new` RENAME TO `book_formatting_preferences`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_book_formatting_preferences_serverId` " +
                        "ON `book_formatting_preferences` (`serverId`)"
                )
            }
        }

        // Per-device readaloud resume position so narration resumes where it stopped after leaving
        // and re-entering a book. Keyed like reading_positions; never synced.
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `readaloud_resume_positions` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`href` TEXT NOT NULL, " +
                        "`progression` REAL, " +
                        "`fragmentRef` TEXT, " +
                        "`localUpdatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`serverId`, `itemId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_readaloud_resume_positions_serverId` " +
                        "ON `readaloud_resume_positions` (`serverId`)"
                )
            }
        }

        // Adds the book's `language` to `library_items` (ABS metadata.language), surfaced on the
        // Library Item Detail Screen and used as a Filtered Books facet (ADR 0027). Nullable — books
        // with no language set carry NULL.
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `language` TEXT")
            }
        }

        // 28 → 29: record whether an ABS Library Item carries audio. `hasAudio` decides which matched
        // item is the audio target — possibly a different item than the ebook being read when a
        // library splits ebooks and audiobooks (ADR 0019). Defaults to 0; refreshed from ABS
        // (`numAudioFiles`/`numTracks`) on every library sync.
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `hasAudio` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 29 → 30: the matched audiobook's total length, sent with audiobook progress so ABS reports
        // a real percentage rather than 0%. Defaults to 0; refreshed from ABS `duration` on every
        // library sync. Separate from 28 → 29 so a device already at v29 (which only added hasAudio)
        // upgrades cleanly instead of mismatching the schema.
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_items` ADD COLUMN `audioDurationSec` REAL NOT NULL DEFAULT 0")
            }
        }

        // Re-keys `libraries` from (id) to (serverId, id). Library ids are unique only within an
        // Audiobookshelf instance, so two Servers pointing at the same instance emit identical ids
        // (issue #113); the bare-id PK let one Server's libraries overwrite the other's. Adds the
        // serverId FK-cascade (libraries previously relied on manual cleanup in ServerRepository)
        // and a serverId index, matching `library_items`. Orphan rows whose serverId no longer
        // references a Server are dropped so the new FK holds.
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `libraries_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`serverId` TEXT NOT NULL, " +
                        "`isUnsupported` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`serverId`, `id`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `libraries_new` (id, name, mediaType, serverId, isUnsupported) " +
                        "SELECT id, name, mediaType, serverId, isUnsupported FROM `libraries` " +
                        "WHERE serverId IN (SELECT id FROM `servers`)"
                )
                db.execSQL("DROP TABLE `libraries`")
                db.execSQL("ALTER TABLE `libraries_new` RENAME TO `libraries`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_libraries_serverId` ON `libraries` (`serverId`)"
                )
            }
        }

        // Per-book audio playback settings (ADR 0028). Device-local, never synced. Keyed by a resolved
        // audio identity (serverId, bookId) — the linked audiobook's ABS id when present, else the
        // Storyteller readaloud id — so a Readaloud and its audiobook share one record. serverId
        // FK-cascades. `speed` is nullable; a row exists only when the user overrides the 1x default.
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audio_playback_preferences` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`speed` REAL, " +
                        "PRIMARY KEY(`serverId`, `bookId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_playback_preferences_serverId` " +
                        "ON `audio_playback_preferences` (`serverId`)"
                )
            }
        }

        // Durable local audiobook listen position (ADR 0029): book-absolute seconds + the wall-clock
        // it was last set at, keyed by (serverId, itemId) with serverId FK-cascade — mirrors
        // `reading_positions`. Server-synced (a last-update-wins peer against ABS), unlike the
        // device-local readaloud resume table.
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audiobook_positions` (" +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`positionSec` REAL NOT NULL, " +
                        "`localUpdatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`serverId`, `itemId`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audiobook_positions_serverId` " +
                        "ON `audiobook_positions` (`serverId`)"
                )
            }
        }

        // Durable offline-reconcile marker (ADR 0030): add `lastSyncedAt` to both position tables.
        // A row is dirty when localUpdatedAt > lastSyncedAt; the sweep worker pushes dirty rows when
        // online. Default 0 ⇒ every existing row is dirty once and reconciled GET-before-PATCH (safe).
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reading_positions` ADD COLUMN `lastSyncedAt` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `audiobook_positions` ADD COLUMN `lastSyncedAt` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book_formatting_preferences` ADD COLUMN `showReadingTimeEstimate` INTEGER DEFAULT NULL")
            }
        }

        // Adds text context columns to annotations so the rendering Locator can disambiguate
        // multiple occurrences of the same word on a page (Readium's text-search uses before/after).
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `annotations` ADD COLUMN `textBefore` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `annotations` ADD COLUMN `textAfter` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Audiobook bookmarks (a COLLECTION of titled book-absolute positions per item, unlike the
        // single-value audiobook_positions). Dirty-tracking + soft-delete mirror ADR 0030. serverId
        // FK-cascades; indexed by serverId and (serverId, itemId) for per-item lookups.
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audiobook_bookmarks` (" +
                        "`id` TEXT NOT NULL, " +
                        "`serverId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, " +
                        "`positionSec` REAL NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`localUpdatedAt` INTEGER NOT NULL, " +
                        "`lastSyncedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audiobook_bookmarks_serverId` " +
                        "ON `audiobook_bookmarks` (`serverId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audiobook_bookmarks_serverId_itemId` " +
                        "ON `audiobook_bookmarks` (`serverId`, `itemId`)"
                )
            }
        }
    }
}
