package com.riffle.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android/JVM counterpart of `IosRiffleDatabaseSchemaTest.allDaoTablesExistAfterSchemaCreate`
 * and `noSpuriousTablesAreCreated` (core/database/src/iosTest): pins the complete set of tables
 * Room creates for [RiffleDatabase].
 *
 * Catches an `@Entity` left registered in `RiffleDatabase.entities` after a feature is removed,
 * and an entity that a DAO queries but that was never added to `entities = [...]`.
 */
class RiffleDatabaseSchemaTablesTest {

    private val expectedTables = setOf(
        "sources",
        "libraries",
        "library_items",
        "series",
        "series_items",
        "collections",
        "collection_items",
        "reading_positions",
        "book_formatting_preferences",
        "readaloud_links",
        "readaloud_candidates",
        "readaloud_dismissals",
        "cross_epub_index",
        "annotations",
        "readaloud_resume_positions",
        "audio_playback_preferences",
        "audiobook_positions",
        "audiobook_bookmarks",
        "toc_cache",
        "audiobook_chapter_cache",
        "local_files_folders",
        "local_files_files",
        "local_files_file_folders",
        "local_file_metadata_overrides",
        "remote_item_freshness",
        "playlists",
        "playlist_items",
        "publication_metrics_cache",
        "book_comic_formatting_preferences",
        "dictionary_packs",
        "lookup_history",
        "cover_grid_scale",
    )

    /** Bookkeeping tables Room/SQLite own; not part of the entity contract. */
    private val infrastructureTables = setOf(
        "room_master_table",
        "android_metadata",
        "sqlite_sequence",
    )

    @Test
    fun allEntityTablesExistAndNoSpuriousTablesAreCreated() = runTest {
        val directory = Files.createTempDirectory("riffle-room-schema-test").toFile()
        val dbPath = directory.resolve("riffle.db").absolutePath
        try {
            val database = openRiffleDatabase(dbPath)
            try {
                // Force Room to open a connection and create the schema.
                database.sourceDao().getById("no-such-source")
            } finally {
                database.close()
            }

            val actual = allTableNames(dbPath) - infrastructureTables

            val missing = expectedTables - actual
            val unknown = actual - expectedTables
            assertTrue(missing.isEmpty(), "Missing tables after schema creation: $missing")
            assertTrue(unknown.isEmpty(), "Unexpected tables created by Room: $unknown")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun allTableNames(dbPath: String): Set<String> {
        val connection = BundledSQLiteDriver().open(dbPath)
        return try {
            val names = mutableSetOf<String>()
            val statement = connection.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            )
            try {
                while (statement.step()) names.add(statement.getText(0))
            } finally {
                statement.close()
            }
            names
        } finally {
            connection.close()
        }
    }
}
