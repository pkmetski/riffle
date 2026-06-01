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
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported, ebookFileIno, ebookFormat) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 0, 1, NULL, 'epub')"
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
    fun migration21To22_addsReadaloudLinksKeyedByAbsItemWithCascade() {
        helper.createDatabase(TEST_DB, 21).use { db ->
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

        // New table starts empty.
        db.query("SELECT COUNT(*) FROM readaloud_links").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // Insert a Confirmed auto-link.
        db.execSQL(
            "INSERT INTO readaloud_links " +
                "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                "VALUES ('abs1', 'item-ebook', 'st1', 'book-42', 'CONFIRMED', 0, 1000, 1000)"
        )

        // Insert a second row for the same Storyteller readaloud pointing at a different
        // ABS item — represents the audiobook stub in an Audiobooks library.
        db.execSQL(
            "INSERT INTO readaloud_links " +
                "(absServerId, absLibraryItemId, storytellerServerId, storytellerBookId, state, userConfirmed, createdAt, updatedAt) " +
                "VALUES ('abs1', 'item-audio', 'st1', 'book-42', 'CONFIRMED', 0, 1100, 1100)"
        )

        // The Storyteller readaloud now legitimately has two rows.
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

        // FK cascade: deleting the ABS server wipes every row referencing it.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM servers WHERE id = 'abs1'")
        db.query("SELECT COUNT(*) FROM readaloud_links").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        // And cascade on the Storyteller side too.
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
    fun migration22To23_addsIsbnAndAsinColumns() {
        helper.createDatabase(TEST_DB, 22).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt, addedAt) " +
                    "VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, NULL, 'epub', NULL, NULL, NULL, '', NULL, NULL, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 23, true, RiffleDatabase.MIGRATION_22_23)

        // Pre-existing row preserved; new columns default to NULL.
        db.query("SELECT id, title, isbn, asin FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }

        // New writes can populate the new columns.
        db.execSQL(
            "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, ebookFileIno, ebookFormat, description, seriesName, publishedYear, genres, publisher, lastOpenedAt, addedAt, isbn, asin) " +
                "VALUES ('item2', 'lib1', 'Atomic Habits', 'James Clear', NULL, 0.0, NULL, 'epub', NULL, NULL, NULL, '', NULL, NULL, NULL, '9780735211292', 'B07D23CFGR')"
        )
        db.query("SELECT isbn, asin FROM library_items WHERE id = 'item2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("9780735211292", cursor.getString(0))
            assertEquals("B07D23CFGR", cursor.getString(1))
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
            TEST_DB, 24, true,
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
        )

        db.query("SELECT url, username, serverType FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("http://localhost", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("AUDIOBOOKSHELF", cursor.getString(2))
        }
    }
}
