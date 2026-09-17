package com.riffle.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.riffle.core.database.dao.IosAudiobookPositionDao
import com.riffle.core.database.dao.IosBookComicFormattingPreferencesDao
import com.riffle.core.database.dao.IosBookFormattingPreferencesDao
import com.riffle.core.database.dao.IosReadingPositionDao
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals(
            setOf(
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("url", "TEXT", true),
                ColumnSpec("isActive", "INTEGER", true),
                ColumnSpec("insecureConnectionAllowed", "INTEGER", true),
                ColumnSpec("username", "TEXT", true),
                ColumnSpec("serverType", "TEXT", true),
                ColumnSpec("absUserId", "TEXT", false),
                ColumnSpec("type", "TEXT", true),
            ),
            tableColumnSpecs("sources"),
        )
    }

    // ── Table: libraries ─────────────────────────────────────────────────────

    @Test
    fun librariesTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("name", "TEXT", true),
                ColumnSpec("mediaType", "TEXT", true),
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("isUnsupported", "INTEGER", true),
            ),
            tableColumnSpecs("libraries"),
        )
    }

    // ── Table: library_items ─────────────────────────────────────────────────

    @Test
    fun libraryItemsTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("libraryId", "TEXT", true),
                ColumnSpec("title", "TEXT", true),
                ColumnSpec("author", "TEXT", true),
                ColumnSpec("coverUrl", "TEXT", false),
                ColumnSpec("readingProgress", "REAL", true),
                ColumnSpec("ebookFileIno", "TEXT", false),
                ColumnSpec("ebookFormat", "TEXT", true),
                ColumnSpec("hasAudio", "INTEGER", true),
                ColumnSpec("audioDurationSec", "REAL", true),
                ColumnSpec("description", "TEXT", false),
                ColumnSpec("seriesName", "TEXT", false),
                ColumnSpec("seriesSequence", "TEXT", false),
                ColumnSpec("publishedYear", "TEXT", false),
                ColumnSpec("genres", "TEXT", true),
                ColumnSpec("publisher", "TEXT", false),
                ColumnSpec("language", "TEXT", false),
                ColumnSpec("lastOpenedAt", "INTEGER", false),
                ColumnSpec("addedAt", "INTEGER", true),
                ColumnSpec("isbn", "TEXT", false),
                ColumnSpec("asin", "TEXT", false),
                ColumnSpec("finishedAt", "INTEGER", false),
                ColumnSpec("pageCount", "INTEGER", false),
            ),
            tableColumnSpecs("library_items"),
        )
    }

    // ── Table: toc_cache ─────────────────────────────────────────────────────

    @Test
    fun tocCacheTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("ebookFileIno", "TEXT", true),
                ColumnSpec("entriesJson", "TEXT", true),
                ColumnSpec("cachedAt", "INTEGER", true),
            ),
            tableColumnSpecs("toc_cache"),
        )
    }

    // ── Table: playlists ─────────────────────────────────────────────────────

    @Test
    fun playlistsTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("rootId", "TEXT", true),
                ColumnSpec("name", "TEXT", true),
                ColumnSpec("bookCount", "INTEGER", true),
            ),
            tableColumnSpecs("playlists"),
        )
    }

    @Test
    fun playlistItemsTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("playlistId", "TEXT", true),
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("orderIndex", "INTEGER", true),
            ),
            tableColumnSpecs("playlist_items"),
        )
    }

    // ── Table: annotations ───────────────────────────────────────────────────

    @Test
    fun annotationsTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("type", "TEXT", true),
                ColumnSpec("cfi", "TEXT", true),
                ColumnSpec("color", "TEXT", true),
                ColumnSpec("note", "TEXT", false),
                ColumnSpec("textSnippet", "TEXT", true),
                ColumnSpec("textBefore", "TEXT", true),
                ColumnSpec("textAfter", "TEXT", true),
                ColumnSpec("chapterHref", "TEXT", true),
                ColumnSpec("spineIndex", "INTEGER", true),
                ColumnSpec("progression", "REAL", true),
                ColumnSpec("bookmarkTitle", "TEXT", true),
                ColumnSpec("createdAt", "INTEGER", true),
                ColumnSpec("updatedAt", "INTEGER", true),
                ColumnSpec("originDeviceId", "TEXT", true),
                ColumnSpec("lastModifiedByDeviceId", "TEXT", true),
                ColumnSpec("deleted", "INTEGER", true),
                ColumnSpec("lastSyncedAt", "INTEGER", true),
                ColumnSpec("embeddedFigures", "TEXT", false),
                ColumnSpec("imageHref", "TEXT", false),
                ColumnSpec("imageSvg", "TEXT", false),
                ColumnSpec("imageBytes", "TEXT", false),
                ColumnSpec("originFontFamily", "TEXT", false),
                ColumnSpec("emphasisStyles", "TEXT", false),
                ColumnSpec("textSnippetHtml", "TEXT", false),
                ColumnSpec("fragmentAnchor", "TEXT", false),
            ),
            tableColumnSpecs("annotations"),
        )
    }

    // ── Tables: series / series_items / collections / collection_items ────────
    // IosSourceDao issues DELETE … WHERE sourceId = ? against these tables on
    // source deletion, so they must exist even though no full DAO is active.

    @Test
    fun seriesTableHasRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("libraryId", "TEXT", true),
                ColumnSpec("name", "TEXT", true),
                ColumnSpec("coverUrl", "TEXT", false),
                ColumnSpec("bookCount", "INTEGER", true),
            ),
            tableColumnSpecs("series"),
        )
    }

    @Test
    fun seriesItemsTableHasRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("seriesId", "TEXT", true),
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("sequenceOrder", "REAL", false),
            ),
            tableColumnSpecs("series_items"),
        )
    }

    @Test
    fun collectionsTableHasRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("id", "TEXT", true),
                ColumnSpec("libraryId", "TEXT", true),
                ColumnSpec("name", "TEXT", true),
                ColumnSpec("bookCount", "INTEGER", true),
            ),
            tableColumnSpecs("collections"),
        )
    }

    @Test
    fun collectionItemsTableHasRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("collectionId", "TEXT", true),
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
            ),
            tableColumnSpecs("collection_items"),
        )
    }

    @Test
    fun allDaoTablesExistAfterSchemaCreate() {
        val expected = setOf(
            "sources", "libraries", "library_items", "toc_cache",
            "playlists", "playlist_items", "annotations",
            "series", "series_items", "collections", "collection_items",
            "local_files_folders", "local_files_files", "local_files_file_folders",
            "reading_positions", "audiobook_positions",
            "book_formatting_preferences", "book_comic_formatting_preferences",
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
            "reading_positions", "audiobook_positions",
            "book_formatting_preferences", "book_comic_formatting_preferences",
        )
        val actual = allTableNames()
        val unknown = actual - knownTables
        assertTrue(unknown.isEmpty(), "Unexpected tables created by DDL: $unknown")
    }

    // ── Table: reading_positions ──────────────────────────────────────────────

    @Test
    fun readingPositionsTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("cfi", "TEXT", true),
                ColumnSpec("localUpdatedAt", "INTEGER", true),
                ColumnSpec("lastSyncedAt", "INTEGER", true),
                ColumnSpec("deleted", "INTEGER", true),
            ),
            tableColumnSpecs("reading_positions"),
        )
    }

    // ── Table: audiobook_positions ────────────────────────────────────────────

    @Test
    fun audiobookPositionsTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("positionSec", "REAL", true),
                ColumnSpec("localUpdatedAt", "INTEGER", true),
                ColumnSpec("lastSyncedAt", "INTEGER", true),
                ColumnSpec("deleted", "INTEGER", true),
            ),
            tableColumnSpecs("audiobook_positions"),
        )
    }

    // ── Table: book_formatting_preferences ────────────────────────────────────

    @Test
    fun bookFormattingPreferencesTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("itemId", "TEXT", true),
                ColumnSpec("screenDimensionBucket", "TEXT", true),
                ColumnSpec("fontSize", "REAL", false),
                ColumnSpec("theme", "TEXT", false),
                ColumnSpec("fontFamily", "TEXT", false),
                ColumnSpec("lineSpacing", "REAL", false),
                ColumnSpec("margins", "REAL", false),
                ColumnSpec("orientation", "TEXT", false),
                ColumnSpec("showChapterMap", "INTEGER", false),
                ColumnSpec("coloredChapterMap", "INTEGER", false),
                ColumnSpec("showReadingProgressLabels", "INTEGER", false),
                ColumnSpec("showCurrentChapterLabel", "INTEGER", false),
                ColumnSpec("doublePageSpread", "INTEGER", false),
                ColumnSpec("justifyText", "INTEGER", false),
                ColumnSpec("showReadingTimeEstimate", "INTEGER", false),
            ),
            tableColumnSpecs("book_formatting_preferences"),
        )
    }

    // ── Table: book_comic_formatting_preferences ──────────────────────────────

    @Test
    fun bookComicFormattingPreferencesTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("source_id", "TEXT", true),
                ColumnSpec("item_id", "TEXT", true),
                ColumnSpec("background_theme", "TEXT", false),
                ColumnSpec("panel_view_on", "INTEGER", false),
                ColumnSpec("panel_overflow", "TEXT", false),
                ColumnSpec("panel_animation_speed_ms", "INTEGER", false),
            ),
            tableColumnSpecs("book_comic_formatting_preferences"),
        )
    }

    // ── Tables: local_files_* ─────────────────────────────────────────────────
    //
    // The expected column lists mirror the constructor properties of the entities in
    // core:database-api one-for-one (LocalFilesFolderEntity, LocalFilesFileEntity,
    // LocalFilesFileFolderEntity). Those are what the iOS DAOs bind by name, so a column the
    // entity declares but the DDL omits is a runtime crash on a fresh install.

    @Test
    fun localFilesFoldersTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("treeUri", "TEXT", true),
                ColumnSpec("displayName", "TEXT", true),
                ColumnSpec("addedAtEpochMs", "INTEGER", true),
                ColumnSpec("libraryId", "TEXT", true),
            ),
            tableColumnSpecs("local_files_folders"),
        )
    }

    @Test
    fun localFilesFilesTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("sourceItemId", "TEXT", true),
                ColumnSpec("originalUri", "TEXT", true),
                ColumnSpec("copiedPath", "TEXT", true),
                ColumnSpec("coverPath", "TEXT", false),
                ColumnSpec("format", "TEXT", true),
                ColumnSpec("sizeBytes", "INTEGER", true),
                ColumnSpec("mtimeEpochMs", "INTEGER", true),
                ColumnSpec("lastSeenAtEpochMs", "INTEGER", true),
                ColumnSpec("displayName", "TEXT", true),
            ),
            tableColumnSpecs("local_files_files"),
        )
    }

    @Test
    fun localFilesFileFoldersTableHasAllRequiredColumns() {
        assertEquals(
            setOf(
                ColumnSpec("sourceId", "TEXT", true),
                ColumnSpec("sourceItemId", "TEXT", true),
                ColumnSpec("folderTreeUri", "TEXT", true),
                ColumnSpec("lastSeenAtEpochMs", "INTEGER", true),
            ),
            tableColumnSpecs("local_files_file_folders"),
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

    // ── Migration: v3 → v4 ───────────────────────────────────────────────────

    @Test
    fun migrateV3ToV4CreatesPositionAndPreferencesTables() {
        // Start from a genuine v3 state: drop all four tables that v4 creates so that
        // CREATE TABLE IF NOT EXISTS in the migration body is actually exercised.
        val v4Tables = listOf(
            "reading_positions",
            "audiobook_positions",
            "book_formatting_preferences",
            "book_comic_formatting_preferences",
        )
        v4Tables.forEach { table ->
            driver.execute(null, "DROP TABLE IF EXISTS $table", 0)
        }
        val beforeMigration = allTableNames()
        v4Tables.forEach { table ->
            assertTrue(table !in beforeMigration, "$table must be absent before the v3→v4 migration runs")
        }

        IosRiffleDatabaseSchema.migrate(driver, 3L, 4L)

        val tables = allTableNames()
        v4Tables.forEach { table ->
            assertTrue(table in tables, "$table must exist after v3→v4 migration")
        }
    }

    // ── DAO round-trips on a fresh schema ────────────────────────────────────
    //
    // These are the assertions that would have failed on v3, proving the tables exist and the
    // DAO SQL matches the DDL. Each test upserts a row and reads it back to confirm both the
    // table and the column set are correct.

    @Test
    fun readingPositionDaoRoundTrip() = runTest {
        // Insert a source row first (FK constraint).
        insertSource("src1")
        val dao = IosReadingPositionDao(driver, IosInvalidator())
        val entity = ReadingPositionEntity(
            sourceId = "src1",
            itemId = "item1",
            cfi = "epubcfi(/6/4[c01]!/4/2/1:0)",
            localUpdatedAt = 1000L,
            lastSyncedAt = 900L,
            deleted = false,
        )
        dao.upsert(entity)
        val fetched = dao.getByItemId("src1", "item1")
        assertNotNull(fetched, "Row must be readable after upsert")
        assertEquals(entity, fetched)
        assertNull(dao.getByItemId("src1", "missing"), "Non-existent row must return null")
    }

    @Test
    fun audiobookPositionDaoRoundTrip() = runTest {
        insertSource("src2")
        val dao = IosAudiobookPositionDao(driver, IosInvalidator())
        val entity = AudiobookPositionEntity(
            sourceId = "src2",
            itemId = "item2",
            positionSec = 123.456,
            localUpdatedAt = 2000L,
            lastSyncedAt = 1900L,
            deleted = false,
        )
        dao.upsert(entity)
        val fetched = dao.getByItemId("src2", "item2")
        assertNotNull(fetched, "Row must be readable after upsert")
        assertEquals(entity, fetched)
    }

    @Test
    fun bookFormattingPreferencesDaoRoundTrip() = runTest {
        insertSource("src3")
        val dao = IosBookFormattingPreferencesDao(driver, IosInvalidator())
        val entity = BookFormattingPreferencesEntity(
            sourceId = "src3",
            itemId = "item3",
            screenDimensionBucket = "Compact_Medium",
            fontSize = 1.2f,
            theme = "dark",
            fontFamily = "serif",
        )
        dao.upsert(entity)
        val fetched = dao.getByItemId("src3", "item3", "Compact_Medium")
        assertNotNull(fetched, "Row must be readable after upsert")
        assertEquals(entity, fetched)
        assertNull(dao.getByItemId("src3", "item3", "Expanded_Medium"), "Different bucket must return null")
    }

    @Test
    fun bookComicFormattingPreferencesDaoRoundTrip() = runTest {
        insertSource("src4")
        val dao = IosBookComicFormattingPreferencesDao(driver, IosInvalidator())
        val entity = BookComicFormattingPreferencesEntity(
            sourceId = "src4",
            itemId = "item4",
            backgroundTheme = "dark",
            panelViewOn = true,
            panelOverflow = "scroll",
            panelAnimationSpeedMs = 300,
        )
        dao.upsert(entity)
        val fetched = dao.getByItemId("src4", "item4")
        assertNotNull(fetched, "Row must be readable after upsert")
        assertEquals(entity, fetched)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Name + SQLite type + NOT NULL constraint for a single column. */
    private data class ColumnSpec(val name: String, val type: String, val notNull: Boolean)

    /**
     * Returns the full [ColumnSpec] set for [table] by querying PRAGMA table_info.
     * PRAGMA columns: cid(0), name(1), type(2), notnull(3), dflt_value(4), pk(5).
     */
    private fun tableColumnSpecs(table: String): Set<ColumnSpec> =
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                val specs = mutableSetOf<ColumnSpec>()
                while (cursor.next().value) {
                    specs.add(
                        ColumnSpec(
                            name = cursor.getString(1)!!,
                            type = cursor.getString(2) ?: "",
                            notNull = cursor.getLong(3) == 1L,
                        )
                    )
                }
                QueryResult.Value(specs)
            },
            0,
        ).value

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

    private fun insertSource(id: String) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sources (id, url, isActive, insecureConnectionAllowed, username, serverType, type) VALUES (?, '', 0, 0, '', 'AUDIOBOOKSHELF', 'ABS')",
            1,
        ) { bindString(0, id) }
    }
}
