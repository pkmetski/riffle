package com.riffle.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RiffleDatabase::class.java,
    )

    @Test
    fun migration1To2() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, RiffleDatabase.MIGRATION_1_2)

        db.query("SELECT url, displayName FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("http://localhost", cursor.getString(0))
            assertEquals("My Server", cursor.getString(1))
        }
        db.query("SELECT COUNT(*) FROM libraries").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM library_items").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration2To3() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO libraries (id, name, mediaType, serverId) VALUES ('lib1', 'Books', 'book', 's1')"
            )
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, RiffleDatabase.MIGRATION_2_3)

        db.query("SELECT id, name, mediaType, serverId, isUnsupported FROM libraries WHERE id = 'lib1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("lib1", cursor.getString(0))
            assertEquals("Books", cursor.getString(1))
            assertEquals("book", cursor.getString(2))
            assertEquals("s1", cursor.getString(3))
            assertEquals(0, cursor.getInt(4)) // isUnsupported defaults to 0
        }
        db.query("SELECT id, title, author, readingProgress, isDownloaded, isSupported FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertEquals("Herbert", cursor.getString(2))
            assertEquals(0.5, cursor.getDouble(3), 0.001)
            assertEquals(0, cursor.getInt(4))
            assertEquals(1, cursor.getInt(5)) // isSupported defaults to 1
        }
    }

    @Test
    fun migration3To4() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('lib1', 'Books', 'book', 's1', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, RiffleDatabase.MIGRATION_3_4)

        db.query("SELECT id, name, mediaType, serverId, isUnsupported FROM libraries WHERE id = 'lib1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("lib1", cursor.getString(0))
            assertEquals("Books", cursor.getString(1))
            assertEquals("book", cursor.getString(2))
            assertEquals("s1", cursor.getString(3))
            assertEquals(0, cursor.getInt(4))
        }
        db.query("SELECT COUNT(*) FROM series").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM collections").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM series_items").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM collection_items").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration4To5() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.25, 0, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, RiffleDatabase.MIGRATION_4_5)

        db.query("SELECT id, title, author, readingProgress, isDownloaded, isSupported, ebookFileIno FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertEquals("Herbert", cursor.getString(2))
            assertEquals(0.25, cursor.getDouble(3), 0.001)
            assertEquals(0, cursor.getInt(4))
            assertEquals(1, cursor.getInt(5))
            assertNull(cursor.getString(6)) // ebookFileIno defaults to NULL
        }
        db.query("SELECT COUNT(*) FROM reading_positions").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration5To6() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported, ebookFileIno) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.25, 0, 1, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, RiffleDatabase.MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM book_formatting_preferences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration6To7() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation) VALUES ('item1', 1.0, 'Light', 'Serif', 1.2, 1.0, 'Horizontal')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, RiffleDatabase.MIGRATION_6_7)

        db.query("SELECT itemId, showChapterMap FROM book_formatting_preferences WHERE itemId = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals(1, cursor.getInt(1)) // showChapterMap defaults to 1 (true)
        }
    }

    @Test
    fun migration7To8() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported, ebookFileIno, ebookFormat) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 0, 1, NULL, 'epub')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, RiffleDatabase.MIGRATION_7_8)

        db.query("SELECT id, title, description, seriesName, publishedYear, genres, publisher FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertNull(cursor.getString(2))   // description defaults to NULL
            assertNull(cursor.getString(3))   // seriesName defaults to NULL
            assertNull(cursor.getString(4))   // publishedYear defaults to NULL
            assertEquals("", cursor.getString(5)) // genres defaults to empty string
            assertNull(cursor.getString(6))   // publisher defaults to NULL
        }
    }

    @Test
    fun migration8To9() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                // genres (added at 7→8, NOT NULL) has only a Kotlin default, not a SQL one, so it
                // isn't in the generated CREATE — the seed must supply it.
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported, ebookFileIno, ebookFormat, genres) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 0, 1, NULL, 'epub', '')"
            )
            db.execSQL(
                "INSERT INTO reading_positions (itemId, cfi) VALUES ('item-1', 'epubcfi(/6/4!/4/1:0)')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, RiffleDatabase.MIGRATION_8_9)

        db.query("SELECT id, title, lastOpenedAt FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertNull(cursor.getString(2)) // lastOpenedAt defaults to NULL
        }
        db.query("SELECT itemId, cfi, localUpdatedAt FROM reading_positions WHERE itemId = 'item-1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item-1", cursor.getString(0))
            assertEquals("epubcfi(/6/4!/4/1:0)", cursor.getString(1))
            assertEquals(0L, cursor.getLong(2)) // localUpdatedAt defaults to 0
        }
    }

    @Test
    fun migration9To10() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt) " +
                    "VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 1, 1, NULL, 'epub', 'A desc', NULL, '1965', '', NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, RiffleDatabase.MIGRATION_9_10)

        db.query("SELECT id, title, author, readingProgress, isSupported, ebookFormat FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertEquals("Herbert", cursor.getString(2))
            assertEquals(0.5, cursor.getDouble(3), 0.001)
            assertEquals(1, cursor.getInt(4))
            assertEquals("epub", cursor.getString(5))
        }
        // isDownloaded column must not exist
        db.query("PRAGMA table_info(library_items)").use { cursor ->
            val colNames = buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("isDownloaded must be removed", !colNames.contains("isDownloaded"))
        }
    }

    @Test
    fun migration10To11() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isSupported, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt) " +
                    "VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 1, NULL, 'epub', 'A desc', NULL, '1965', '', NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, RiffleDatabase.MIGRATION_10_11)

        db.query("SELECT id, title, ebookFormat FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertEquals("epub", cursor.getString(2))
        }
        db.query("PRAGMA table_info(library_items)").use { cursor ->
            val colNames = buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("isSupported must be removed", !colNames.contains("isSupported"))
        }
    }

    @Test
    fun migration11To12() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt) " +
                    "VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, NULL, 'epub', NULL, NULL, NULL, '', NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, RiffleDatabase.MIGRATION_11_12)

        db.query("SELECT id, title, addedAt FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertNull(cursor.getString(2)) // addedAt defaults to NULL
        }
    }

    @Test
    fun migration12To13() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap) VALUES ('item1', 1.0, 'Light', 'Serif', 1.2, 1.0, 'Horizontal', 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, RiffleDatabase.MIGRATION_12_13)

        db.query("SELECT itemId, doublePageSpread FROM book_formatting_preferences WHERE itemId = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1)) // doublePageSpread defaults to 0 (false)
        }
    }

    @Test
    fun migration13To14() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) " +
                    "VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, RiffleDatabase.MIGRATION_13_14)

        db.query("SELECT id, url, displayName, username FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals("http://localhost", cursor.getString(1))
            assertEquals("My Server", cursor.getString(2))
            assertEquals("", cursor.getString(3))
        }
    }

    @Test
    fun migration14To15() {
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread) " +
                    "VALUES ('item1', 1.0, 'Light', 'Serif', 1.2, 1.0, 'Horizontal', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, RiffleDatabase.MIGRATION_14_15)

        db.query("SELECT itemId, justifyText FROM book_formatting_preferences WHERE itemId = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1)) // justifyText defaults to 0 (false)
        }
    }

    @Test
    fun migration15To16() {
        helper.createDatabase(TEST_DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread, justifyText) " +
                    "VALUES ('item1', 1.3, 'Dark', 'Serif', 1.5, 1.2, 'Vertical', 1, 0, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, RiffleDatabase.MIGRATION_15_16)

        // Existing rows survive verbatim — they become explicit overrides, not null/follow-global.
        db.query(
            "SELECT itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread, justifyText " +
                "FROM book_formatting_preferences WHERE itemId = 'item1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals(1.3, cursor.getDouble(1), 0.001)
            assertEquals("Dark", cursor.getString(2))
            assertEquals("Serif", cursor.getString(3))
            assertEquals(1.5, cursor.getDouble(4), 0.001)
            assertEquals(1.2, cursor.getDouble(5), 0.001)
            assertEquals("Vertical", cursor.getString(6))
            assertEquals(1, cursor.getInt(7))
            assertEquals(0, cursor.getInt(8))
            assertEquals(1, cursor.getInt(9))
        }

        // New writes can store NULL on any non-PK column ("follow global").
        db.execSQL(
            "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread, justifyText) " +
                "VALUES ('item2', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1)"
        )
        db.query(
            "SELECT fontSize, theme, justifyText FROM book_formatting_preferences WHERE itemId = 'item2'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertTrue("fontSize should be null", cursor.isNull(0))
            assertTrue("theme should be null", cursor.isNull(1))
            assertEquals(1, cursor.getInt(2))
        }
    }

    @Test
    fun migration16To17() {
        helper.createDatabase(TEST_DB, 16).use { db ->
            // Existing row with sparse overrides — only showChapterMap is set.
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread, justifyText) " +
                    "VALUES ('item1', NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, RiffleDatabase.MIGRATION_16_17)

        // Pre-existing data preserved; new column defaults to NULL = follow global.
        db.query(
            "SELECT itemId, showChapterMap, showReadingProgressLabels FROM book_formatting_preferences WHERE itemId = 'item1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue("showReadingProgressLabels should be null for legacy rows", cursor.isNull(2))
        }

        // New writes can populate the new column.
        db.execSQL(
            "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, showReadingProgressLabels, doublePageSpread, justifyText) " +
                "VALUES ('item2', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL)"
        )
        db.query(
            "SELECT showReadingProgressLabels FROM book_formatting_preferences WHERE itemId = 'item2'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration17To18() {
        helper.createDatabase(TEST_DB, 17).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username) " +
                    "VALUES ('s1', 'http://localhost', 'My Server', 1, 0, 'plamen')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 18, true, RiffleDatabase.MIGRATION_17_18)

        // Existing rows default to AUDIOBOOKSHELF — backfills the only Server type that existed before this migration.
        db.query("SELECT id, url, displayName, username, serverType FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals("http://localhost", cursor.getString(1))
            assertEquals("My Server", cursor.getString(2))
            assertEquals("plamen", cursor.getString(3))
            assertEquals("AUDIOBOOKSHELF", cursor.getString(4))
        }

        // New Storyteller rows can be inserted with the new value.
        db.execSQL(
            "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username, serverType) " +
                "VALUES ('s2', 'http://media-server:8001', 'My Storyteller', 0, 0, 'plamen', 'STORYTELLER')"
        )
        db.query("SELECT serverType FROM servers WHERE id = 's2'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("STORYTELLER", cursor.getString(0))
        }
    }

    @Test
    fun migration18To19_backfillsActiveServerIdAndCascadeDeletes() {
        helper.createDatabase(TEST_DB, 18).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://localhost', 'Server 1', 1, 0, 'alice', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s2', 'http://other', 'Server 2', 0, 0, 'bob', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO reading_positions (itemId, cfi, localUpdatedAt) VALUES ('item-1', 'epubcfi(/6/4!/4/1:0)', 12345)"
            )
            db.execSQL(
                "INSERT INTO reading_positions (itemId, cfi, localUpdatedAt) VALUES ('item-2', 'epubcfi(/6/8!/4/1:42)', 67890)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 19, true, RiffleDatabase.MIGRATION_18_19)

        // Pre-existing rows are attributed to the active server.
        db.query("SELECT serverId, itemId, cfi, localUpdatedAt FROM reading_positions ORDER BY itemId").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals("item-1", cursor.getString(1))
            assertEquals("epubcfi(/6/4!/4/1:0)", cursor.getString(2))
            assertEquals(12345L, cursor.getLong(3))
            cursor.moveToNext()
            assertEquals("s1", cursor.getString(0))
            assertEquals("item-2", cursor.getString(1))
            assertEquals("epubcfi(/6/8!/4/1:42)", cursor.getString(2))
            assertEquals(67890L, cursor.getLong(3))
        }

        // Different servers can now hold distinct positions for the same itemId.
        db.execSQL(
            "INSERT INTO reading_positions (serverId, itemId, cfi, localUpdatedAt) " +
                "VALUES ('s2', 'item-1', 'epubcfi(/6/4!/4/1:999)', 99999)"
        )
        db.query("SELECT cfi FROM reading_positions WHERE serverId = 's1' AND itemId = 'item-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("epubcfi(/6/4!/4/1:0)", cursor.getString(0))
        }
        db.query("SELECT cfi FROM reading_positions WHERE serverId = 's2' AND itemId = 'item-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("epubcfi(/6/4!/4/1:999)", cursor.getString(0))
        }

        // FK cascade: deleting a server wipes that server's positions but leaves others untouched.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's2'")
        db.query("SELECT COUNT(*) FROM reading_positions WHERE serverId = 's2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM reading_positions WHERE serverId = 's1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun migration18To19_dropsRowsWhenNoActiveServer() {
        helper.createDatabase(TEST_DB, 18).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://localhost', 'Server 1', 0, 0, 'alice', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO reading_positions (itemId, cfi, localUpdatedAt) VALUES ('orphan', 'epubcfi(/6/4!/4/1:0)', 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 19, true, RiffleDatabase.MIGRATION_18_19)

        db.query("SELECT COUNT(*) FROM reading_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration19To20() {
        helper.createDatabase(TEST_DB, 19).use { db ->
            // Existing row with sparse overrides — only showReadingProgressLabels set.
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, showReadingProgressLabels, doublePageSpread, justifyText) " +
                    "VALUES ('item1', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1, NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, RiffleDatabase.MIGRATION_19_20)

        // Pre-existing data preserved; new column defaults to NULL = follow global.
        db.query(
            "SELECT itemId, showReadingProgressLabels, showCurrentChapterLabel FROM book_formatting_preferences WHERE itemId = 'item1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue("showCurrentChapterLabel should be null for legacy rows", cursor.isNull(2))
        }

        // New writes can populate the new column.
        db.execSQL(
            "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, showReadingProgressLabels, showCurrentChapterLabel, doublePageSpread, justifyText) " +
                "VALUES ('item2', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1, NULL, NULL)"
        )
        db.query(
            "SELECT showCurrentChapterLabel FROM book_formatting_preferences WHERE itemId = 'item2'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migration20To21_dropsDisplayNameAndPreservesData() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server:13378', 'media-server', 1, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s2', 'http://media-server:8001', 'media-server', 0, 1, 'plamen', 'STORYTELLER')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true, RiffleDatabase.MIGRATION_20_21)

        // displayName column is gone; every other column survives unchanged.
        db.query("SELECT id, url, isActive, insecureConnectionAllowed, username, serverType FROM servers ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals("http://media-server:13378", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals("plamen", cursor.getString(4))
            assertEquals("AUDIOBOOKSHELF", cursor.getString(5))
            cursor.moveToNext()
            assertEquals("s2", cursor.getString(0))
            assertEquals("http://media-server:8001", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals("plamen", cursor.getString(4))
            assertEquals("STORYTELLER", cursor.getString(5))
        }
    }

    @Test
    fun migration21To22_addsIsbnAsinAndReadaloudLinksKeyedByAbsItem() {
        helper.createDatabase(TEST_DB, 21).use { db ->
            // Pre-existing library_item row that must survive the isbn/asin column add.
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt, addedAt) " +
                    "VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, NULL, 'epub', NULL, NULL, NULL, '', NULL, NULL, NULL)"
            )
            // Two servers — one Storyteller, one ABS — that the link will reference.
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('st1', 'http://media-server:8001', 0, 0, 'plamen', 'STORYTELLER')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('abs1', 'http://media-server:13378', 1, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true, RiffleDatabase.MIGRATION_21_22)

        // isbn/asin columns default to NULL on pre-existing rows.
        db.query("SELECT id, isbn, asin FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
        }
        // New writes can populate them.
        db.execSQL(
            "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt, addedAt, isbn, asin) " +
                "VALUES ('item2', 'lib1', 'Atomic Habits', 'James Clear', NULL, 0.0, NULL, 'epub', NULL, NULL, NULL, '', NULL, NULL, NULL, '9780735211292', 'B07D23CFGR')"
        )
        db.query("SELECT isbn, asin FROM library_items WHERE id = 'item2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("9780735211292", cursor.getString(0))
            assertEquals("B07D23CFGR", cursor.getString(1))
        }

        // readaloud_links starts empty.
        db.query("SELECT COUNT(*) FROM readaloud_links").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A readaloud can hold multiple ABS-side rows — ebook entry + audiobook stub.
        db.execSQL(
            "INSERT INTO readaloud_links " +
                "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                "VALUES ('abs1', 'item-ebook', 'st1', 'book-42', 'CONFIRMED', 0, 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO readaloud_links " +
                "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                "VALUES ('abs1', 'item-audio', 'st1', 'book-42', 'CONFIRMED', 0, 1100, 1100)"
        )
        db.query("SELECT COUNT(*) FROM readaloud_links WHERE storytellerBookId = 'book-42'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        // Primary key is the ABS side: same (absServerId, absLibraryItemId) collides via REPLACE.
        db.execSQL(
            "INSERT OR REPLACE INTO readaloud_links " +
                "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                "VALUES ('abs1', 'item-ebook', 'st1', 'book-42', 'CONFIRMED', 1, 1000, 2000)"
        )
        db.query("SELECT storytellerBookId, userConfirmed, updatedAt FROM readaloud_links WHERE absServerId = 'abs1' AND absLibraryItemId = 'item-ebook'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("book-42", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(2000L, cursor.getLong(2))
        }

        // FK cascade on the ABS side.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 'abs1'")
        db.query("SELECT COUNT(*) FROM readaloud_links").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // FK cascade on the Storyteller side.
        db.execSQL(
            "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                "VALUES ('abs2', 'http://other:13378', 0, 0, 'plamen', 'AUDIOBOOKSHELF')"
        )
        db.execSQL(
            "INSERT INTO readaloud_links " +
                "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                "VALUES ('abs2', 'item-1', 'st1', 'book-99', 'CONFIRMED', 0, 5000, 5000)"
        )
        db.execSQL("DELETE FROM servers WHERE id = 'st1'")
        db.query("SELECT COUNT(*) FROM readaloud_links").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration22To23_addsReadaloudCandidatesAndDismissals() {
        helper.createDatabase(TEST_DB, 22).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('st1', 'http://media-server:8001', 0, 0, 'plamen', 'STORYTELLER')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('abs1', 'http://media-server:13378', 1, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
            // A pre-existing readaloud_link must survive untouched.
            db.execSQL(
                "INSERT INTO readaloud_links " +
                    "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                    "VALUES ('abs1', 'item-ebook', 'st1', 'book-42', 'CONFIRMED', 1, 1000, 1000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 23, true, RiffleDatabase.MIGRATION_22_23)

        // Both new tables start empty.
        db.query("SELECT COUNT(*) FROM readaloud_candidates").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM readaloud_dismissals").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        // The pre-existing link is preserved across the migration.
        db.query("SELECT userConfirmed FROM readaloud_links WHERE absServerId = 'abs1' AND absLibraryItemId = 'item-ebook'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // Candidates can be written with a score; PK is the full (readaloud, ABS item) pair.
        db.execSQL(
            "INSERT INTO readaloud_candidates " +
                "(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId, score) " +
                "VALUES ('st1', 'book-7', 'abs1', 'cand-a', 0.91)"
        )
        db.execSQL(
            "INSERT INTO readaloud_candidates " +
                "(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId, score) " +
                "VALUES ('st1', 'book-7', 'abs1', 'cand-b', 0.88)"
        )
        db.query("SELECT score FROM readaloud_candidates WHERE storytellerBookId = 'book-7' AND absLibraryItemId = 'cand-a'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(0.91, cursor.getDouble(0), 0.0001)
        }
        db.query("SELECT COUNT(*) FROM readaloud_candidates WHERE storytellerBookId = 'book-7'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        // Dismissals: a per-book "don't ask again" (empty ABS ids) and a per-candidate dismissal
        // coexist for the same book because the ABS ids are part of the key.
        db.execSQL(
            "INSERT INTO readaloud_dismissals " +
                "(storytellerServerId, storytellerBookId, scope, absServerId, absLibraryItemId) " +
                "VALUES ('st1', 'book-9', 'BOOK', '', '')"
        )
        db.execSQL(
            "INSERT INTO readaloud_dismissals " +
                "(storytellerServerId, storytellerBookId, scope, absServerId, absLibraryItemId) " +
                "VALUES ('st1', 'book-9', 'CANDIDATE', 'abs1', 'cand-x')"
        )
        db.query("SELECT COUNT(*) FROM readaloud_dismissals WHERE storytellerBookId = 'book-9'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        // FK cascade clears candidates when the ABS server is removed.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 'abs1'")
        db.query("SELECT COUNT(*) FROM readaloud_candidates").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        // Removing the Storyteller server cascades candidates and dismissals away.
        db.execSQL("DELETE FROM servers WHERE id = 'st1'")
        db.query("SELECT COUNT(*) FROM readaloud_dismissals").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }


    @Test
    fun migration23To24_addsCrossEpubIndexCacheTable() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            // A pre-existing readaloud_links row that must survive the new table being added.
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('st1', 'http://media-server:8001', 0, 0, 'plamen', 'STORYTELLER')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('abs1', 'http://media-server:13378', 1, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO readaloud_links " +
                    "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                    "VALUES ('abs1', 'item1', 'st1', 'book-1', 'CONFIRMED', 0, 1000, 1000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, RiffleDatabase.MIGRATION_23_24)

        // Pre-existing data survives.
        db.query("SELECT COUNT(*) FROM readaloud_links").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // New cache table exists and starts empty.
        db.query("SELECT COUNT(*) FROM cross_epub_index").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A row keyed by both checksums round-trips.
        db.execSQL(
            "INSERT INTO cross_epub_index (absEpubChecksum, storytellerEpubChecksum, perChapterMapsBlob, builtAt) " +
                "VALUES ('absChk', 'stChk', '[{\"absChars\":12,\"storytellerChars\":12}]', 5000)"
        )
        db.query("SELECT perChapterMapsBlob, builtAt FROM cross_epub_index WHERE absEpubChecksum = 'absChk' AND storytellerEpubChecksum = 'stChk'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("[{\"absChars\":12,\"storytellerChars\":12}]", cursor.getString(0))
            assertEquals(5000L, cursor.getLong(1))
        }

        // A different checksum on either side is a distinct row (invalidation = keyed miss).
        db.execSQL(
            "INSERT INTO cross_epub_index (absEpubChecksum, storytellerEpubChecksum, perChapterMapsBlob, builtAt) " +
                "VALUES ('absChk2', 'stChk', '[]', 6000)"
        )
        db.query("SELECT COUNT(*) FROM cross_epub_index").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        // Same composite key collides via REPLACE.
        db.execSQL(
            "INSERT OR REPLACE INTO cross_epub_index (absEpubChecksum, storytellerEpubChecksum, perChapterMapsBlob, builtAt) " +
                "VALUES ('absChk', 'stChk', '[]', 9000)"
        )
        db.query("SELECT builtAt FROM cross_epub_index WHERE absEpubChecksum = 'absChk' AND storytellerEpubChecksum = 'stChk'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(9000L, cursor.getLong(0))
        }
    }

    @Test
    fun migration24To25_addsSyncReadyAnnotationsStoreKeyedByAbsItem() {
        helper.createDatabase(TEST_DB, 24).use { db ->
            // An ABS server the annotation will reference (FK target).
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('abs1', 'http://media-server:13378', 1, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, RiffleDatabase.MIGRATION_24_25)

        // The new table starts empty.
        db.query("SELECT COUNT(*) FROM annotations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A full sync-ready highlight row — CFI *range*, snippet, chapter href, provenance,
        // tombstone flag — writes and reads back intact.
        db.execSQL(
            "INSERT INTO annotations " +
                "(id, serverId, itemId, type, cfi, color, note, textSnippet, chapterHref, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                "VALUES ('uuid-1', 'abs1', 'item-1', 'HIGHLIGHT', 'epubcfi(/6/4!/4/2,/1:0,/1:10)', 'yellow', NULL, 'hello world', 'chap01.xhtml', 1000, 1000, 'device-A', 'device-A', 0)"
        )
        db.query(
            "SELECT serverId, itemId, type, cfi, color, note, textSnippet, chapterHref, originDeviceId, lastModifiedByDeviceId, deleted " +
                "FROM annotations WHERE id = 'uuid-1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("abs1", cursor.getString(0))
            assertEquals("item-1", cursor.getString(1))
            assertEquals("HIGHLIGHT", cursor.getString(2))
            assertEquals("epubcfi(/6/4!/4/2,/1:0,/1:10)", cursor.getString(3))
            assertEquals("yellow", cursor.getString(4))
            assertNull(cursor.getString(5))
            assertEquals("hello world", cursor.getString(6))
            assertEquals("chap01.xhtml", cursor.getString(7))
            assertEquals("device-A", cursor.getString(8))
            assertEquals("device-A", cursor.getString(9))
            assertEquals(0, cursor.getInt(10))
        }

        // A note (nullable column populated) coexists with the highlight.
        db.execSQL(
            "INSERT INTO annotations " +
                "(id, serverId, itemId, type, cfi, color, note, textSnippet, chapterHref, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                "VALUES ('uuid-2', 'abs1', 'item-1', 'HIGHLIGHT', 'epubcfi(/6/4!/6/2,/1:0,/1:4)', 'yellow', 'my thought', 'word', 'chap01.xhtml', 1100, 1100, 'device-A', 'device-A', 0)"
        )
        db.query("SELECT note FROM annotations WHERE id = 'uuid-2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("my thought", cursor.getString(0))
        }

        // FK cascade: removing the ABS server clears its annotations.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 'abs1'")
        db.query("SELECT COUNT(*) FROM annotations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    // 25 → 26: key Library Items by (serverId, itemId) end-to-end (issue #81, ADR 0025).
    // `library_items` gains a `serverId` column + composite PK (serverId, id), backfilled from
    // each item's owning library (libraryId → libraries.serverId). The series_items /
    // collection_items join tables and book_formatting_preferences gain `serverId` in their PKs,
    // backfilled by resolving itemId → library_items → libraries.serverId. This lets two
    // Storyteller Servers each hold a book "1" without collision. Orphan rows whose item/library
    // no longer resolves to a Server are dropped.
    @Test
    fun migration25To26_keysLibraryItemsByServerAndItem() {
        helper.createDatabase(TEST_DB, 25).use { db ->
            // Two Storyteller servers, each with one library.
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://story-a', 1, 0, 'plamen', 'STORYTELLER')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s2', 'http://story-b', 0, 0, 'plamen', 'STORYTELLER')"
            )
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('libA', 'Books A', 'book', 's1', 0)")
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('libB', 'Books B', 'book', 's2', 0)")

            // Pre-migration the PK is itemId alone, so the two servers' book "1" can't both exist
            // yet; seed item "1" on s1 and item "2" on s2.
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres) " +
                    "VALUES ('1', 'libA', 'War and Peace', 'Tolstoy', NULL, 0.25, 'epub', '')"
            )
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres) " +
                    "VALUES ('2', 'libB', 'Picture Book', 'Anon', NULL, 0.5, 'epub', '')"
            )

            // Series / collection groupings and formatting prefs for s1's book "1".
            db.execSQL("INSERT INTO series (id, libraryId, name, coverUrl, bookCount) VALUES ('ser1', 'libA', 'Classics', NULL, 1)")
            db.execSQL("INSERT INTO collections (id, libraryId, name, bookCount) VALUES ('col1', 'libA', 'Favourites', 1)")
            db.execSQL("INSERT INTO series_items (seriesId, itemId, sequenceOrder) VALUES ('ser1', '1', 1.0)")
            db.execSQL("INSERT INTO collection_items (collectionId, itemId) VALUES ('col1', '1')")
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme) VALUES ('1', 18.0, 'sepia')"
            )

            // An orphan formatting-pref row whose item no longer exists — must be dropped.
            db.execSQL(
                "INSERT INTO book_formatting_preferences (itemId, fontSize, theme) VALUES ('ghost', 99.0, 'dark')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, RiffleDatabase.MIGRATION_25_26)

        // library_items: serverId backfilled from the owning library, data preserved.
        db.query("SELECT serverId, libraryId, title, readingProgress FROM library_items WHERE id = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals("libA", cursor.getString(1))
            assertEquals("War and Peace", cursor.getString(2))
            assertEquals(0.25f, cursor.getFloat(3), 0.0001f)
        }
        db.query("SELECT serverId FROM library_items WHERE id = '2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("s2", cursor.getString(0))
        }

        // Composite PK now lets the two servers' book "1" coexist as distinct rows.
        db.execSQL(
            "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres) " +
                "VALUES ('s2', '1', 'libB', 'A Different Book', 'Someone', NULL, 0.0, 'epub', '')"
        )
        db.query("SELECT title FROM library_items WHERE serverId = 's1' AND id = '1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("War and Peace", cursor.getString(0))
        }
        db.query("SELECT title FROM library_items WHERE serverId = 's2' AND id = '1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("A Different Book", cursor.getString(0))
        }

        // Join tables carry serverId, backfilled from the item's owning server.
        db.query("SELECT serverId, sequenceOrder FROM series_items WHERE seriesId = 'ser1' AND itemId = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals(1.0f, cursor.getFloat(1), 0.0001f)
        }
        db.query("SELECT serverId FROM collection_items WHERE collectionId = 'col1' AND itemId = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
        }

        // Formatting prefs: serverId backfilled, value preserved; orphan dropped.
        db.query("SELECT serverId, fontSize, theme FROM book_formatting_preferences WHERE itemId = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals(18.0f, cursor.getFloat(1), 0.0001f)
            assertEquals("sepia", cursor.getString(2))
        }
        db.query("SELECT COUNT(*) FROM book_formatting_preferences WHERE itemId = 'ghost'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // Two servers' formatting for book "1" no longer collide.
        db.execSQL(
            "INSERT INTO book_formatting_preferences (serverId, itemId, fontSize, theme) VALUES ('s2', '1', 32.0, 'dark')"
        )
        db.query("SELECT fontSize FROM book_formatting_preferences WHERE serverId = 's1' AND itemId = '1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(18.0f, cursor.getFloat(0), 0.0001f)
        }
        db.query("SELECT fontSize FROM book_formatting_preferences WHERE serverId = 's2' AND itemId = '1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(32.0f, cursor.getFloat(0), 0.0001f)
        }

        // FK cascade: removing a server clears its library items.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's2'")
        db.query("SELECT COUNT(*) FROM library_items WHERE serverId = 's2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM library_items WHERE serverId = 's1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migration26To27_addsReadaloudResumePositionsKeyedByServerAndItem() {
        helper.createDatabase(TEST_DB, 26).use { db ->
            // A server the resume position will reference (FK target), plus a pre-existing reading
            // position that must survive the new table being added.
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server:8001', 1, 0, 'plamen', 'STORYTELLER')"
            )
            db.execSQL(
                "INSERT INTO reading_positions (serverId, itemId, cfi, localUpdatedAt) " +
                    "VALUES ('s1', 'item-1', 'epubcfi(/6/2!/4/1:0)', 1000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 27, true, RiffleDatabase.MIGRATION_26_27)

        // Pre-existing data survives.
        db.query("SELECT cfi FROM reading_positions WHERE serverId = 's1' AND itemId = 'item-1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("epubcfi(/6/2!/4/1:0)", cursor.getString(0))
        }

        // New table exists and starts empty.
        db.query("SELECT COUNT(*) FROM readaloud_resume_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A full resume row round-trips, including nullable progression/fragmentRef populated.
        db.execSQL(
            "INSERT INTO readaloud_resume_positions (serverId, itemId, href, progression, fragmentRef, localUpdatedAt) " +
                "VALUES ('s1', 'item-1', 'chap03.xhtml', 0.42, 'chap03.xhtml#sent12', 5000)"
        )
        db.query(
            "SELECT href, progression, fragmentRef, localUpdatedAt FROM readaloud_resume_positions " +
                "WHERE serverId = 's1' AND itemId = 'item-1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("chap03.xhtml", cursor.getString(0))
            assertEquals(0.42, cursor.getDouble(1), 1e-9)
            assertEquals("chap03.xhtml#sent12", cursor.getString(2))
            assertEquals(5000L, cursor.getLong(3))
        }

        // Nullable columns accept NULL (close with no resolvable column/sentence).
        db.execSQL(
            "INSERT INTO readaloud_resume_positions (serverId, itemId, href, progression, fragmentRef, localUpdatedAt) " +
                "VALUES ('s1', 'item-2', 'chap01.xhtml', NULL, NULL, 6000)"
        )
        db.query("SELECT progression, fragmentRef FROM readaloud_resume_positions WHERE serverId = 's1' AND itemId = 'item-2'").use { cursor ->
            cursor.moveToFirst()
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }

        // FK cascade: removing the server clears its resume positions.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM readaloud_resume_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration27To28_addsLanguageColumnToLibraryItems() {
        helper.createDatabase(TEST_DB, 27).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('lib1', 'Books', 'book', 's1', 0)")
            // A pre-existing item with all the columns the migration must preserve.
            db.execSQL(
                "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres, publishedYear) " +
                    "VALUES ('s1', '1', 'lib1', 'The Colour of Magic', 'Terry Pratchett', NULL, 0.3, 'epub', 'Fiction', '1983')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 28, true, RiffleDatabase.MIGRATION_27_28)

        // Pre-existing data preserved; new language column defaults to NULL.
        db.query("SELECT title, publishedYear, language FROM library_items WHERE serverId = 's1' AND id = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("The Colour of Magic", cursor.getString(0))
            assertEquals("1983", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }

        // The new column accepts a value.
        db.execSQL("UPDATE library_items SET language = 'English' WHERE serverId = 's1' AND id = '1'")
        db.query("SELECT language FROM library_items WHERE serverId = 's1' AND id = '1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("English", cursor.getString(0))
        }
    }

    @Test
    fun migration28To29_addsHasAudioColumn() {
        helper.createDatabase(TEST_DB, 28).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://localhost', 1, 0, '', 'AUDIOBOOKSHELF')"
            )
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('libA', 'Books', 'book', 's1', 0)")
            db.execSQL(
                "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres) " +
                    "VALUES ('s1', '1', 'libA', 'Foundation''s Edge', 'Asimov', NULL, 0.25, 'epub', '')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 29, true, RiffleDatabase.MIGRATION_28_29)

        // New hasAudio column defaults to 0 and pre-existing data is preserved.
        db.query("SELECT hasAudio, title, readingProgress FROM library_items WHERE serverId = 's1' AND id = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
            assertEquals("Foundation's Edge", cursor.getString(1))
            assertEquals(0.25f, cursor.getFloat(2), 0.0001f)
        }

        // The column is writable as a real audio flag.
        db.execSQL(
            "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres, hasAudio) " +
                "VALUES ('s1', '2', 'libA', 'Audiobook', 'Asimov', NULL, 0.0, 'unsupported', '', 1)"
        )
        db.query("SELECT hasAudio FROM library_items WHERE serverId = 's1' AND id = '2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migration29To30_addsAudioDurationSecColumn() {
        helper.createDatabase(TEST_DB, 29).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://localhost', 1, 0, '', 'AUDIOBOOKSHELF')"
            )
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('libA', 'Books', 'book', 's1', 0)")
            db.execSQL(
                "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres, hasAudio) " +
                    "VALUES ('s1', '1', 'libA', 'Foundation''s Edge', 'Asimov', NULL, 0.25, 'epub', '', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 30, true, RiffleDatabase.MIGRATION_29_30)

        // New audioDurationSec column defaults to 0; pre-existing data (incl. hasAudio) preserved.
        db.query("SELECT audioDurationSec, hasAudio, title FROM library_items WHERE serverId = 's1' AND id = '1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(0.0, cursor.getDouble(0), 0.0001)
            assertEquals(0, cursor.getInt(1))
            assertEquals("Foundation's Edge", cursor.getString(2))
        }

        // The column is writable as a real duration.
        db.execSQL(
            "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, genres, hasAudio, audioDurationSec) " +
                "VALUES ('s1', '2', 'libA', 'Audiobook', 'Asimov', NULL, 0.0, 'unsupported', '', 1, 39214.5)"
        )
        db.query("SELECT audioDurationSec FROM library_items WHERE serverId = 's1' AND id = '2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(39214.5, cursor.getDouble(0), 0.0001)
        }
    }

    @Test
    fun migration30To31_reKeysLibrariesByServerIdAndId() {
        helper.createDatabase(TEST_DB, 30).use { db ->
            // Two Servers on the same Audiobookshelf instance (issue #113).
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s2', 'http://media-server', 0, 0, 'plamen', 'AUDIOBOOKSHELF')"
            )
            // A pre-existing library owned by s1. Under the v30 bare-id PK only one row per id can
            // exist, so the collision can't be pre-seeded here — the migration must enable it.
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('lib', 'Books', 'book', 's1', 1)")
            // An orphan whose serverId references no Server — dropped so the new FK holds.
            db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('orphan', 'Ghost', 'book', 'ghost', 0)")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 31, true, RiffleDatabase.MIGRATION_30_31)

        // s1's library is preserved with all its column values.
        db.query("SELECT name, mediaType, isUnsupported FROM libraries WHERE serverId = 's1' AND id = 'lib'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Books", cursor.getString(0))
            assertEquals("book", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
        // The orphan row was dropped.
        db.query("SELECT COUNT(*) FROM libraries WHERE id = 'orphan'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        // The composite key now lets the other Server own a same-id library — the heart of #113.
        db.execSQL("INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('lib', 'Audiobooks', 'book', 's2', 0)")
        db.query("SELECT name FROM libraries WHERE id = 'lib' ORDER BY serverId").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("Books", cursor.getString(0))
            cursor.moveToNext()
            assertEquals("Audiobooks", cursor.getString(0))
        }
        // FK cascade: removing a Server clears its libraries (and only its own).
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT serverId FROM libraries WHERE id = 'lib'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s2", cursor.getString(0))
        }
    }

    @Test
    fun migration31To32_addsAudioPlaybackPreferencesTable() {
        helper.createDatabase(TEST_DB, 31).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 32, true, RiffleDatabase.MIGRATION_31_32)

        // The new table exists and is empty (no rows are backfilled — absence means the 1x default).
        db.query("SELECT COUNT(*) FROM audio_playback_preferences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A per-book speed override is writable and reads back.
        db.execSQL(
            "INSERT INTO audio_playback_preferences (serverId, bookId, speed) VALUES ('s1', '42', 1.5)"
        )
        db.query("SELECT speed FROM audio_playback_preferences WHERE serverId = 's1' AND bookId = '42'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1.5, cursor.getDouble(0), 0.0001)
        }

        // FK cascade: removing the Server clears its audio settings.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM audio_playback_preferences").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration32To33_addsAudiobookPositionsTable() {
        helper.createDatabase(TEST_DB, 32).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 33, true, RiffleDatabase.MIGRATION_32_33)

        // The new table exists and is empty (no rows are backfilled).
        db.query("SELECT COUNT(*) FROM audiobook_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // A position row is writable and reads back with its seconds + timestamp intact.
        db.execSQL(
            "INSERT INTO audiobook_positions (serverId, itemId, positionSec, localUpdatedAt) " +
                "VALUES ('s1', '42', 123.5, 1700000000000)"
        )
        db.query(
            "SELECT positionSec, localUpdatedAt FROM audiobook_positions WHERE serverId = 's1' AND itemId = '42'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(123.5, cursor.getDouble(0), 0.0001)
            assertEquals(1700000000000L, cursor.getLong(1))
        }

        // FK cascade: removing the Server clears its audiobook positions.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM audiobook_positions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration33To34_addsLastSyncedAtToBothPositionTables() {
        helper.createDatabase(TEST_DB, 33).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            // Pre-existing rows in both position tables (v33 shape: no lastSyncedAt yet).
            db.execSQL(
                "INSERT INTO reading_positions (serverId, itemId, cfi, localUpdatedAt) " +
                    "VALUES ('s1', '10', 'epubcfi(/6/4!/4)', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO audiobook_positions (serverId, itemId, positionSec, localUpdatedAt) " +
                    "VALUES ('s1', '20', 321.5, 1700000000001)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 34, true, RiffleDatabase.MIGRATION_33_34)

        // The ebook row is preserved and the new column defaults to 0 (⇒ dirty: localUpdatedAt > 0).
        db.query(
            "SELECT cfi, localUpdatedAt, lastSyncedAt FROM reading_positions WHERE serverId = 's1' AND itemId = '10'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("epubcfi(/6/4!/4)", cursor.getString(0))
            assertEquals(1700000000000L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
        }

        // The audiobook row is preserved and likewise gains lastSyncedAt defaulting to 0.
        db.query(
            "SELECT positionSec, localUpdatedAt, lastSyncedAt FROM audiobook_positions WHERE serverId = 's1' AND itemId = '20'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(321.5, cursor.getDouble(0), 0.0001)
            assertEquals(1700000000001L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
        }

        // The new column is writable on both tables.
        db.execSQL("UPDATE reading_positions SET lastSyncedAt = 1700000000000 WHERE serverId = 's1' AND itemId = '10'")
        db.query("SELECT lastSyncedAt FROM reading_positions WHERE serverId = 's1' AND itemId = '10'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1700000000000L, cursor.getLong(0))
        }
    }

    @Test
    fun migration34To35_addsAudiobookBookmarksTable() {
        helper.createDatabase(TEST_DB, 34).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 35, true, RiffleDatabase.MIGRATION_34_35)

        db.query("SELECT COUNT(*) FROM audiobook_bookmarks").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }

        db.execSQL(
            "INSERT INTO audiobook_bookmarks (id, serverId, itemId, positionSec, title, createdAt, localUpdatedAt, lastSyncedAt, deleted) " +
                "VALUES ('b1', 's1', '42', 765.0, 'The Egg', 1700000000000, 1700000000000, 0, 0)"
        )
        db.query(
            "SELECT positionSec, title, createdAt, localUpdatedAt, lastSyncedAt, deleted FROM audiobook_bookmarks WHERE id = 'b1'"
        ).use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals(765.0, c.getDouble(0), 0.0001)
            assertEquals("The Egg", c.getString(1))
            assertEquals(1700000000000L, c.getLong(2))
            assertEquals(1700000000000L, c.getLong(3))
            assertEquals(0L, c.getLong(4))
            assertEquals(0, c.getInt(5))
        }

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 's1'")
        db.query("SELECT COUNT(*) FROM audiobook_bookmarks").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun migration35To36_addsShowReadingTimeEstimateColumn() {
        helper.createDatabase(TEST_DB, 35).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            // A pre-existing formatting-pref row with all v35 columns.
            db.execSQL(
                "INSERT INTO book_formatting_preferences (serverId, itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, showReadingProgressLabels, showCurrentChapterLabel, doublePageSpread, justifyText) " +
                    "VALUES ('s1', 'item1', 1.3, 'Dark', 'Serif', 1.5, 1.2, 'Vertical', 1, 1, 0, 0, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 36, true, RiffleDatabase.MIGRATION_35_36)

        // Pre-existing data preserved; new column defaults to NULL = follow global.
        db.query(
            "SELECT serverId, itemId, fontSize, theme, justifyText, showReadingTimeEstimate FROM book_formatting_preferences WHERE itemId = 'item1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("s1", cursor.getString(0))
            assertEquals("item1", cursor.getString(1))
            assertEquals(1.3, cursor.getDouble(2), 0.001)
            assertEquals("Dark", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
            assertTrue("showReadingTimeEstimate should be null for legacy rows", cursor.isNull(5))
        }

        // New writes can populate the new column.
        db.execSQL(
            "INSERT INTO book_formatting_preferences (serverId, itemId, showReadingTimeEstimate) " +
                "VALUES ('s1', 'item2', 1)"
        )
        db.query(
            "SELECT showReadingTimeEstimate FROM book_formatting_preferences WHERE itemId = 'item2'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migration36To37_addsTextContextColumnsToAnnotations() {
        helper.createDatabase(TEST_DB, 36).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            // A pre-existing annotation row with all v36 columns (no textBefore/textAfter yet).
            db.execSQL(
                "INSERT INTO annotations (id, serverId, itemId, type, cfi, color, textSnippet, chapterHref, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                    "VALUES ('a1', 's1', 'book1', 'HIGHLIGHT', 'epubcfi(/6/4!/4/2,/1:0,/1:7)', 'yellow', 'meaning', 'chapter1.xhtml', 1000, 1000, 'dev', 'dev', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 37, true, RiffleDatabase.MIGRATION_36_37)

        // Pre-existing annotation preserved; new columns default to empty string.
        db.query(
            "SELECT id, textSnippet, textBefore, textAfter FROM annotations WHERE id = 'a1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("a1", cursor.getString(0))
            assertEquals("meaning", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals("", cursor.getString(3))
        }

        // New annotations with context round-trip correctly.
        db.execSQL(
            "INSERT INTO annotations (id, serverId, itemId, type, cfi, color, textSnippet, textBefore, textAfter, chapterHref, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                "VALUES ('a2', 's1', 'book1', 'HIGHLIGHT', 'epubcfi(/6/4!/4/2,/1:8,/1:15)', 'green', 'example', 'before context', 'after context', 'chapter1.xhtml', 2000, 2000, 'dev', 'dev', 0)"
        )
        db.query("SELECT textBefore, textAfter FROM annotations WHERE id = 'a2'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("before context", cursor.getString(0))
            assertEquals("after context", cursor.getString(1))
        }
    }

    @Test
    fun migration37To38_addsPositionAndTitleColumnsToAnnotations() {
        helper.createDatabase(TEST_DB, 37).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            // Pre-existing highlight row with all v37 columns (no spineIndex/progression/bookmarkTitle yet).
            db.execSQL(
                "INSERT INTO annotations (id, serverId, itemId, type, cfi, color, textSnippet, textBefore, textAfter, chapterHref, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                    "VALUES ('a1', 's1', 'book1', 'HIGHLIGHT', 'epubcfi(/6/4!/4/2,/1:0,/1:7)', 'yellow', 'meaning', '', '', 'ch1.xhtml', 1000, 1000, 'dev', 'dev', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 38, true, RiffleDatabase.MIGRATION_37_38)

        // Pre-existing row preserved; new columns default correctly.
        db.query(
            "SELECT id, spineIndex, progression, bookmarkTitle FROM annotations WHERE id = 'a1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("a1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))         // spineIndex DEFAULT 0
            assertEquals(0.0, cursor.getDouble(2), 0.0001) // progression DEFAULT 0.0
            assertEquals("", cursor.getString(3))     // bookmarkTitle DEFAULT ''
        }

        // New bookmark row with populated fields round-trips correctly.
        db.execSQL(
            "INSERT INTO annotations (id, serverId, itemId, type, cfi, color, textSnippet, textBefore, textAfter, chapterHref, spineIndex, progression, bookmarkTitle, createdAt, updatedAt, originDeviceId, lastModifiedByDeviceId, deleted) " +
                "VALUES ('b1', 's1', 'book1', 'BOOKMARK', 'epubcfi(/6/6!/4/1:0)', '', '', '', '', 'ch2.xhtml', 2, 0.42, 'Where it gets weird', 2000, 2000, 'dev', 'dev', 0)"
        )
        db.query("SELECT spineIndex, progression, bookmarkTitle FROM annotations WHERE id = 'b1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
            assertEquals(0.42, cursor.getDouble(1), 0.001)
            assertEquals("Where it gets weird", cursor.getString(2))
        }
    }

    @Test
    fun migration38To39_addsIdentityResultToReadaloudLinks() {
        helper.createDatabase(TEST_DB, 38).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('abs', 'http://abs', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('st', 'http://st', 0, 0, 'test', 'STORYTELLER')"
            )
            db.execSQL(
                "INSERT INTO readaloud_links " +
                    "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                    "VALUES ('abs', 'item1', 'st', '42', 'CONFIRMED', 1, 100, 200)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 39, true, RiffleDatabase.MIGRATION_38_39)

        // The pre-existing link is preserved and its new identity verdict defaults to UNKNOWN.
        db.query(
            "SELECT identityResult, userConfirmed, storytellerBookId, createdAt FROM readaloud_links " +
                "WHERE absServerId = 'abs' AND absLibraryItemId = 'item1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("UNKNOWN", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("42", cursor.getString(2))
            assertEquals(100L, cursor.getLong(3))
        }

        // The verdict column is writable.
        db.execSQL("UPDATE readaloud_links SET identityResult = 'VERIFIED' WHERE absServerId = 'abs'")
        db.query("SELECT identityResult FROM readaloud_links WHERE absServerId = 'abs'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("VERIFIED", cursor.getString(0))
        }
    }

    @Test
    fun migrateFullChain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 39, true,
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
            RiffleDatabase.MIGRATION_37_38,
            RiffleDatabase.MIGRATION_38_39,
            RiffleDatabase.MIGRATION_39_40,
        )

        db.query("SELECT url, username, serverType FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("http://localhost", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("AUDIOBOOKSHELF", cursor.getString(2))
        }
    }

    @Test
    fun migration39To40_addsFinishedAtToLibraryItems() {
        helper.createDatabase(TEST_DB, 39).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                    "VALUES ('abs', 'http://abs', 1, 0, 'test', 'AUDIOBOOKSHELF')"
            )
            db.execSQL(
                "INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) " +
                    "VALUES ('lib1', 'Books', 'book', 'abs', 0)"
            )
            db.execSQL(
                "INSERT INTO library_items (serverId, id, libraryId, title, author, coverUrl, readingProgress, " +
                    "ebookFormat, genres, hasAudio, audioDurationSec) " +
                    "VALUES ('abs', 'item1', 'lib1', 'Dune', 'Herbert', NULL, 1.0, 'epub', '', 0, 0.0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 40, true, RiffleDatabase.MIGRATION_39_40)

        // Pre-existing rows get NULL finishedAt — column exists and is nullable.
        db.query("SELECT finishedAt FROM library_items WHERE id = 'item1' AND serverId = 'abs'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertNull(cursor.getLong(0).takeIf { !cursor.isNull(0) })
        }

        // Column is writable.
        db.execSQL("UPDATE library_items SET finishedAt = 1700000000000 WHERE id = 'item1'")
        db.query("SELECT finishedAt FROM library_items WHERE id = 'item1' AND serverId = 'abs'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1700000000000L, cursor.getLong(0))
        }
    }
}

