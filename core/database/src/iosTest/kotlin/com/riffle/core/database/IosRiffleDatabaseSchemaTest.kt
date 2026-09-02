package com.riffle.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that IosRiffleDatabaseSchema creates the correct DDL for all tables accessed
 * by iOS DAOs, and that schema migrations run without error.
 *
 * Column parity is checked against the Room schema for the same tables. If an iOS DAO
 * starts using a column, it must be present here too — otherwise the DAO will crash at
 * runtime on a fresh install.
 */
class IosRiffleDatabaseSchemaTest {

    private lateinit var dbPath: String
    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setUp() {
        dbPath = "${NSTemporaryDirectory()}riffle-schema-test-${NSUUID().UUIDString}.db"
        driver = NativeSqliteDriver(IosRiffleDatabaseSchema, dbPath)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        remove(dbPath)
        remove("$dbPath-shm")
        remove("$dbPath-wal")
    }

    // ── Schema version ────────────────────────────────────────────────────────

    @Test
    fun schemaVersionIsOne() {
        assertEquals(1L, IosRiffleDatabaseSchema.version)
    }

    // ── Table: sources ────────────────────────────────────────────────────────

    @Test
    fun sourcesTableHasAllRequiredColumns() {
        val cols = tableColumns("sources")
        assertTrue(cols.containsAll(listOf(
            "id", "url", "isActive", "insecureConnectionAllowed",
            "username", "serverType", "absUserId", "type",
        )), "sources columns: $cols")
    }

    // ── Table: libraries ─────────────────────────────────────────────────────

    @Test
    fun librariesTableHasAllRequiredColumns() {
        val cols = tableColumns("libraries")
        assertTrue(cols.containsAll(listOf(
            "id", "name", "mediaType", "sourceId", "isUnsupported",
        )), "libraries columns: $cols")
    }

    // ── Table: library_items ─────────────────────────────────────────────────

    @Test
    fun libraryItemsTableHasAllRequiredColumns() {
        val cols = tableColumns("library_items")
        assertTrue(cols.containsAll(listOf(
            "sourceId", "id", "libraryId", "title", "author", "coverUrl",
            "readingProgress", "ebookFileIno", "ebookFormat", "hasAudio",
            "audioDurationSec", "description", "seriesName", "seriesSequence",
            "publishedYear", "genres", "publisher", "language", "lastOpenedAt",
            "addedAt", "isbn", "asin", "finishedAt", "pageCount",
        )), "library_items columns: $cols")
    }

    // ── Table: toc_cache ─────────────────────────────────────────────────────

    @Test
    fun tocCacheTableHasAllRequiredColumns() {
        val cols = tableColumns("toc_cache")
        assertTrue(cols.containsAll(listOf(
            "sourceId", "itemId", "ebookFileIno", "entriesJson", "cachedAt",
        )), "toc_cache columns: $cols")
    }

    // ── Table: playlists ─────────────────────────────────────────────────────

    @Test
    fun playlistsTableHasAllRequiredColumns() {
        val cols = tableColumns("playlists")
        assertTrue(cols.containsAll(listOf(
            "id", "sourceId", "rootId", "name", "bookCount",
        )), "playlists columns: $cols")
    }

    @Test
    fun playlistItemsTableHasAllRequiredColumns() {
        val cols = tableColumns("playlist_items")
        assertTrue(cols.containsAll(listOf(
            "playlistId", "sourceId", "itemId", "orderIndex",
        )), "playlist_items columns: $cols")
    }

    // ── Table: annotations ───────────────────────────────────────────────────

    @Test
    fun annotationsTableHasAllRequiredColumns() {
        val cols = tableColumns("annotations")
        assertTrue(cols.containsAll(listOf(
            "id", "sourceId", "itemId", "type", "cfi", "color", "note",
            "textSnippet", "textBefore", "textAfter", "chapterHref", "spineIndex",
            "progression", "bookmarkTitle", "createdAt", "updatedAt",
            "originDeviceId", "lastModifiedByDeviceId", "deleted", "lastSyncedAt",
            "embeddedFigures", "imageHref", "imageSvg", "imageBytes",
            "originFontFamily", "emphasisStyles", "textSnippetHtml", "fragmentAnchor",
        )), "annotations columns: $cols")
    }

    // ── Tables: series / series_items / collections / collection_items ────────
    // IosSourceDao issues DELETE … WHERE sourceId = ? against these tables on
    // source deletion, so they must exist even though no full DAO is active.

    @Test
    fun seriesTableHasRequiredColumns() {
        val cols = tableColumns("series")
        assertTrue(cols.containsAll(listOf("id", "sourceId", "libraryId", "name")),
            "series columns: $cols")
    }

    @Test
    fun seriesItemsTableHasRequiredColumns() {
        val cols = tableColumns("series_items")
        assertTrue(cols.containsAll(listOf("seriesId", "sourceId", "itemId", "sequenceOrder")),
            "series_items columns: $cols")
    }

    @Test
    fun collectionsTableHasRequiredColumns() {
        val cols = tableColumns("collections")
        assertTrue(cols.containsAll(listOf("id", "sourceId", "libraryId", "name")),
            "collections columns: $cols")
    }

    @Test
    fun collectionItemsTableHasRequiredColumns() {
        val cols = tableColumns("collection_items")
        assertTrue(cols.containsAll(listOf("collectionId", "sourceId", "itemId")),
            "collection_items columns: $cols")
    }

    // ── Migration continuity ─────────────────────────────────────────────────

    @Test
    fun migrateFromCurrentVersionToCurrentVersionIsNoOp() {
        // Should not throw. When the schema version matches the driver's on-disk version,
        // NativeSqliteDriver does not call migrate() at all; calling it explicitly here
        // verifies the implementation is safe regardless.
        IosRiffleDatabaseSchema.migrate(driver, 1L, 1L)
    }

    @Test
    fun allDaoTablesExistAfterSchemaCreate() {
        val expected = setOf(
            "sources", "libraries", "library_items", "toc_cache",
            "playlists", "playlist_items", "annotations",
            "series", "series_items", "collections", "collection_items",
        )
        val actual = allTableNames()
        val missing = expected - actual
        assertTrue(missing.isEmpty(), "Missing tables after schema creation: $missing")
    }

    @Test
    fun noSpuriousTablesAreCreated() {
        // Every table in the DDL must be accounted for in the known set.
        val knownTables = setOf(
            "sources", "libraries", "library_items",
            "series", "series_items", "collections", "collection_items",
            "toc_cache", "playlists", "playlist_items", "annotations",
        )
        val actual = allTableNames()
        val unknown = actual - knownTables
        assertTrue(unknown.isEmpty(), "Unexpected tables created by DDL: $unknown")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun tableColumns(table: String): Set<String> =
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) names.add(cursor.getString(1)!!)
                QueryResult.Value(names)
            },
            0,
        ).value

    private fun allTableNames(): Set<String> =
        driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) names.add(cursor.getString(0)!!)
                QueryResult.Value(names)
            },
            0,
        ).value
}
