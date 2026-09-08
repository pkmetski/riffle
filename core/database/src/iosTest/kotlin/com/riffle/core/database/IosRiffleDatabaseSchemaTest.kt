package com.riffle.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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

    private lateinit var dbName: String
    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setUp() {
        // A bare filename, exactly as production passes it (`openRiffleDatabase("riffle.db")` in
        // IosDatabaseKoinModule). SQLiter treats the argument as a NAME and resolves it against
        // its own base path; handing it a full path throws
        // "File … contains a path separator" out of DatabaseConfiguration's checkFilename.
        dbName = "riffle-schema-test-${NSUUID().UUIDString}.db"
        driver = NativeSqliteDriver(IosRiffleDatabaseSchema, dbName)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        DatabaseFileContext.deleteDatabase(dbName)
    }

    // ── Table: sources ────────────────────────────────────────────────────────

    @Test
    fun sourcesTableHasAllRequiredColumns() {
        val cols = tableColumns("sources")
        assertTrue(
            cols.containsAll(
                listOf(
                    "id", "url", "isActive", "insecureConnectionAllowed",
                    "username", "serverType", "absUserId", "type",
                )
            ),
            "sources columns: $cols"
        )
    }

    // ── Table: libraries ─────────────────────────────────────────────────────

    @Test
    fun librariesTableHasAllRequiredColumns() {
        val cols = tableColumns("libraries")
        assertTrue(
            cols.containsAll(
                listOf(
                    "id", "name", "mediaType", "sourceId", "isUnsupported",
                )
            ),
            "libraries columns: $cols"
        )
    }

    // ── Table: library_items ─────────────────────────────────────────────────

    @Test
    fun libraryItemsTableHasAllRequiredColumns() {
        val cols = tableColumns("library_items")
        assertTrue(
            cols.containsAll(
                listOf(
                    "sourceId", "id", "libraryId", "title", "author", "coverUrl",
                    "readingProgress", "ebookFileIno", "ebookFormat", "hasAudio",
                    "audioDurationSec", "description", "seriesName", "seriesSequence",
                    "publishedYear", "genres", "publisher", "language", "lastOpenedAt",
                    "addedAt", "isbn", "asin", "finishedAt", "pageCount",
                )
            ),
            "library_items columns: $cols"
        )
    }

    // ── Table: toc_cache ─────────────────────────────────────────────────────

    @Test
    fun tocCacheTableHasAllRequiredColumns() {
        val cols = tableColumns("toc_cache")
        assertTrue(
            cols.containsAll(
                listOf(
                    "sourceId", "itemId", "ebookFileIno", "entriesJson", "cachedAt",
                )
            ),
            "toc_cache columns: $cols"
        )
    }

    // ── Table: playlists ─────────────────────────────────────────────────────

    @Test
    fun playlistsTableHasAllRequiredColumns() {
        val cols = tableColumns("playlists")
        assertTrue(
            cols.containsAll(
                listOf(
                    "id", "sourceId", "rootId", "name", "bookCount",
                )
            ),
            "playlists columns: $cols"
        )
    }

    @Test
    fun playlistItemsTableHasAllRequiredColumns() {
        val cols = tableColumns("playlist_items")
        assertTrue(
            cols.containsAll(
                listOf(
                    "playlistId", "sourceId", "itemId", "orderIndex",
                )
            ),
            "playlist_items columns: $cols"
        )
    }

    // ── Table: annotations ───────────────────────────────────────────────────

    @Test
    fun annotationsTableHasAllRequiredColumns() {
        val cols = tableColumns("annotations")
        assertTrue(
            cols.containsAll(
                listOf(
                    "id", "sourceId", "itemId", "type", "cfi", "color", "note",
                    "textSnippet", "textBefore", "textAfter", "chapterHref", "spineIndex",
                    "progression", "bookmarkTitle", "createdAt", "updatedAt",
                    "originDeviceId", "lastModifiedByDeviceId", "deleted", "lastSyncedAt",
                    "embeddedFigures", "imageHref", "imageSvg", "imageBytes",
                    "originFontFamily", "emphasisStyles", "textSnippetHtml", "fragmentAnchor",
                )
            ),
            "annotations columns: $cols"
        )
    }

    // ── Tables: series / series_items / collections / collection_items ────────
    // IosSourceDao issues DELETE … WHERE sourceId = ? against these tables on
    // source deletion, so they must exist even though no full DAO is active.

    @Test
    fun seriesTableHasRequiredColumns() {
        val cols = tableColumns("series")
        assertTrue(
            cols.containsAll(listOf("id", "libraryId", "name", "coverUrl", "bookCount")),
            "series columns: $cols"
        )
    }

    @Test
    fun seriesItemsTableHasRequiredColumns() {
        val cols = tableColumns("series_items")
        assertTrue(
            cols.containsAll(listOf("seriesId", "sourceId", "itemId", "sequenceOrder")),
            "series_items columns: $cols"
        )
    }

    @Test
    fun collectionsTableHasRequiredColumns() {
        val cols = tableColumns("collections")
        assertTrue(
            cols.containsAll(listOf("id", "libraryId", "name", "bookCount")),
            "collections columns: $cols"
        )
    }

    @Test
    fun collectionItemsTableHasRequiredColumns() {
        val cols = tableColumns("collection_items")
        assertTrue(
            cols.containsAll(listOf("collectionId", "sourceId", "itemId")),
            "collection_items columns: $cols"
        )
    }

    @Test
    fun allDaoTablesExistAfterSchemaCreate() {
        val expected = setOf(
            "sources", "libraries", "library_items", "toc_cache",
            "playlists", "playlist_items", "annotations",
            "series", "series_items", "collections", "collection_items",
            "local_files_folders", "local_files_files", "local_files_file_folders",
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
            "local_files_folders", "local_files_files", "local_files_file_folders",
        )
        val actual = allTableNames()
        val unknown = actual - knownTables
        assertTrue(unknown.isEmpty(), "Unexpected tables created by DDL: $unknown")
    }

    // ── Tables: local_files_* ─────────────────────────────────────────────────
    //
    // The expected column lists mirror the constructor properties of the entities in
    // core:database-api one-for-one (LocalFilesFolderEntity, LocalFilesFileEntity,
    // LocalFilesFileFolderEntity). Those are what the iOS DAOs bind by name, so a column the
    // entity declares but the DDL omits is a runtime crash on a fresh install.

    @Test
    fun localFilesFoldersTableHasAllRequiredColumns() {
        val cols = tableColumns("local_files_folders")
        assertTrue(
            cols.containsAll(
                listOf(
                    "sourceId", "treeUri", "displayName", "addedAtEpochMs", "libraryId",
                )
            ),
            "local_files_folders columns: $cols"
        )
    }

    @Test
    fun localFilesFilesTableHasAllRequiredColumns() {
        val cols = tableColumns("local_files_files")
        assertTrue(
            cols.containsAll(
                listOf(
                    "sourceId", "sourceItemId", "originalUri", "copiedPath", "coverPath",
                    "format", "sizeBytes", "mtimeEpochMs", "lastSeenAtEpochMs", "displayName",
                )
            ),
            "local_files_files columns: $cols"
        )
    }

    @Test
    fun localFilesFileFoldersTableHasAllRequiredColumns() {
        val cols = tableColumns("local_files_file_folders")
        assertTrue(
            cols.containsAll(
                listOf(
                    "sourceId", "sourceItemId", "folderTreeUri", "lastSeenAtEpochMs",
                )
            ),
            "local_files_file_folders columns: $cols"
        )
    }

    // ── Migration: v1 → v2 ───────────────────────────────────────────────────

    @Test
    fun migrateV1ToV2CreatesLocalFilesTables() {
        // The driver created in setUp is already at v2 and the migration DDL uses
        // CREATE TABLE IF NOT EXISTS, so simply calling migrate() on the fresh database would
        // pass even if the migration body were deleted. Drop the local_files_* tables first to
        // put the database into a genuine v1 shape, then verify migrate(1, 2) recreates them.
        val localFilesTables = listOf(
            "local_files_file_folders",
            "local_files_files",
            "local_files_folders",
        )
        localFilesTables.forEach { table ->
            driver.execute(null, "DROP TABLE $table", 0)
        }
        val beforeMigration = allTableNames()
        localFilesTables.forEach { table ->
            assertTrue(table !in beforeMigration, "$table must be absent before the v1→v2 migration runs")
        }

        IosRiffleDatabaseSchema.migrate(driver, 1L, 2L)

        val tables = allTableNames()
        assertTrue("local_files_folders" in tables, "local_files_folders must exist after v1→v2 migration")
        assertTrue("local_files_files" in tables, "local_files_files must exist after v1→v2 migration")
        assertTrue("local_files_file_folders" in tables, "local_files_file_folders must exist after v1→v2 migration")
    }

    // ── Migration: v2 → v3 ───────────────────────────────────────────────────

    @Test
    fun migrateV2ToV3RebuildsSeriesAndCollectionsToEntityShape() {
        // Put the tables back into the broken v2 shape (fabricated NOT NULL sourceId, missing
        // coverUrl/bookCount — every DAO insert failed against it), then verify migrate(2, 3)
        // rebuilds them one-for-one with Room's SeriesEntity/CollectionEntity. Fails if the
        // migration body is deleted.
        driver.execute(null, "DROP TABLE series", 0)
        driver.execute(
            null,
            "CREATE TABLE series (id TEXT NOT NULL, sourceId TEXT NOT NULL, libraryId TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY (sourceId, id))",
            0,
        )
        driver.execute(null, "DROP TABLE collections", 0)
        driver.execute(
            null,
            "CREATE TABLE collections (id TEXT NOT NULL, sourceId TEXT NOT NULL, libraryId TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY (sourceId, id))",
            0,
        )

        IosRiffleDatabaseSchema.migrate(driver, 2L, 3L)

        val seriesCols = tableColumns("series")
        assertTrue(
            seriesCols.containsAll(listOf("id", "libraryId", "name", "coverUrl", "bookCount")),
            "series columns after v2→v3: $seriesCols",
        )
        assertTrue("sourceId" !in seriesCols, "series must not carry the fabricated sourceId column")
        val collectionCols = tableColumns("collections")
        assertTrue(
            collectionCols.containsAll(listOf("id", "libraryId", "name", "bookCount")),
            "collections columns after v2→v3: $collectionCols",
        )
        assertTrue("sourceId" !in collectionCols, "collections must not carry the fabricated sourceId column")

        // The IosSeriesDao/IosCollectionDao writes must now succeed against the rebuilt tables.
        driver.execute(
            null,
            "INSERT OR REPLACE INTO series (id, libraryId, name, coverUrl, bookCount) VALUES ('s1', 'lib1', 'Series', NULL, 2)",
            0,
        )
        driver.execute(
            null,
            "INSERT OR REPLACE INTO collections (id, libraryId, name, bookCount) VALUES ('c1', 'lib1', 'Collection', 3)",
            0,
        )
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
