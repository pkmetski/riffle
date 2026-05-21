package com.riffle.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun migrateFullChain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true,
            RiffleDatabase.MIGRATION_1_2,
            RiffleDatabase.MIGRATION_2_3,
            RiffleDatabase.MIGRATION_3_4,
            RiffleDatabase.MIGRATION_4_5,
        )

        db.query("SELECT url, displayName FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("http://localhost", cursor.getString(0))
            assertEquals("My Server", cursor.getString(1))
        }
    }
}
