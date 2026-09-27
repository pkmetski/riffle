package com.riffle.core.database

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

internal object IosRiffleDatabaseSchema : SqlSchema<QueryResult.Value<Unit>> {
    // Tracks the iOS schema version independently of the Android Room schema version.
    // Bumped only when the iOS-side DDL changes; Android Room migrations are irrelevant here.
    override val version: Long = 6L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        (DDL + LOCAL_FILES_DDL + POSITION_AND_PREFS_DDL + FORMERLY_NOOP_DAO_DDL).forEach { driver.execute(null, it, 0) }
        return QueryResult.Value(Unit)
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        if (oldVersion < 2L) {
            LOCAL_FILES_DDL.forEach { driver.execute(null, it, 0) }
        }
        if (oldVersion < 3L) {
            // v1/v2 shipped series/collections tables whose shape never matched the DAO writes
            // (a fabricated NOT NULL sourceId column, missing coverUrl/bookCount) — every insert
            // failed, so the tables are guaranteed empty and can be rebuilt in place.
            SERIES_COLLECTIONS_REBUILD_DDL.forEach { driver.execute(null, it, 0) }
        }
        if (oldVersion < 4L) {
            // v3 had no reading_positions, audiobook_positions, book_formatting_preferences, or
            // book_comic_formatting_preferences tables, causing every book close to crash on iOS.
            POSITION_AND_PREFS_DDL.forEach { driver.execute(null, it, 0) }
        }
        if (oldVersion < 5L) {
            // v4 had no tables for the 14 DAOs that were bound to no-op stubs (issue #1057):
            // readaloud linking/matching, per-book audio prefs, audiobook bookmarks/chapter
            // cache, local-file metadata overrides, remote freshness, publication metrics,
            // dictionary packs, lookup history, and cover-grid scale.
            FORMERLY_NOOP_DAO_DDL.forEach { driver.execute(null, it, 0) }
        }
        if (oldVersion < 6L && newVersion >= 6L) {
            // Adds library_items.progressServerUpdatedAt for last-update-wins so a lagging bulk
            // /api/me pull can't overwrite a fresher per-item value (library-vs-detail bar
            // disagreement). Mirrors Room MIGRATION_74_75.
            driver.execute(
                null,
                "ALTER TABLE library_items ADD COLUMN progressServerUpdatedAt INTEGER NOT NULL DEFAULT 0",
                0,
            )
        }
        return QueryResult.Value(Unit)
    }

    private val DDL = listOf(
        """CREATE TABLE IF NOT EXISTS sources (
            id TEXT NOT NULL PRIMARY KEY,
            url TEXT NOT NULL,
            isActive INTEGER NOT NULL DEFAULT 0,
            insecureConnectionAllowed INTEGER NOT NULL DEFAULT 0,
            username TEXT NOT NULL,
            serverType TEXT NOT NULL DEFAULT 'AUDIOBOOKSHELF',
            absUserId TEXT,
            type TEXT NOT NULL DEFAULT 'ABS'
        )""",

        """CREATE TABLE IF NOT EXISTS libraries (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            mediaType TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            isUnsupported INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (sourceId, id),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_libraries_sourceId ON libraries(sourceId)",

        """CREATE TABLE IF NOT EXISTS library_items (
            sourceId TEXT NOT NULL,
            id TEXT NOT NULL,
            libraryId TEXT NOT NULL,
            title TEXT NOT NULL,
            author TEXT NOT NULL,
            coverUrl TEXT,
            readingProgress REAL NOT NULL DEFAULT 0.0,
            ebookFileIno TEXT,
            ebookFormat TEXT NOT NULL DEFAULT 'unsupported',
            hasAudio INTEGER NOT NULL DEFAULT 0,
            audioDurationSec REAL NOT NULL DEFAULT 0.0,
            description TEXT,
            seriesName TEXT,
            seriesSequence TEXT,
            publishedYear TEXT,
            genres TEXT NOT NULL DEFAULT '',
            publisher TEXT,
            language TEXT,
            lastOpenedAt INTEGER,
            addedAt INTEGER NOT NULL,
            isbn TEXT,
            asin TEXT,
            finishedAt INTEGER,
            pageCount INTEGER,
            progressServerUpdatedAt INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (sourceId, id),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_library_items_sourceId ON library_items(sourceId)",

        // Needed for observeUngroupedByLibraryId subquery filter
        // Mirrors Room's SeriesEntity one-for-one (id PK, no sourceId column — the delete graph
        // resolves ownership through series_items.sourceId, same as Android's SourceDao).
        """CREATE TABLE IF NOT EXISTS series (
            id TEXT NOT NULL PRIMARY KEY,
            libraryId TEXT NOT NULL,
            name TEXT NOT NULL,
            coverUrl TEXT,
            bookCount INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE IF NOT EXISTS series_items (
            seriesId TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            sequenceOrder REAL,
            PRIMARY KEY (seriesId, sourceId, itemId)
        )""",

        // Needed for observeUngroupedByLibraryId subquery filter
        // Mirrors Room's CollectionEntity one-for-one (id PK, no sourceId column).
        """CREATE TABLE IF NOT EXISTS collections (
            id TEXT NOT NULL PRIMARY KEY,
            libraryId TEXT NOT NULL,
            name TEXT NOT NULL,
            bookCount INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE IF NOT EXISTS collection_items (
            collectionId TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            PRIMARY KEY (collectionId, sourceId, itemId)
        )""",

        """CREATE TABLE IF NOT EXISTS toc_cache (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            ebookFileIno TEXT NOT NULL,
            entriesJson TEXT NOT NULL,
            cachedAt INTEGER NOT NULL,
            PRIMARY KEY (sourceId, itemId)
        )""",

        """CREATE TABLE IF NOT EXISTS playlists (
            id TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            rootId TEXT NOT NULL,
            name TEXT NOT NULL,
            bookCount INTEGER NOT NULL,
            PRIMARY KEY (sourceId, id),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_playlists_sourceId ON playlists(sourceId)",
        "CREATE INDEX IF NOT EXISTS index_playlists_rootId ON playlists(rootId)",

        """CREATE TABLE IF NOT EXISTS playlist_items (
            playlistId TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            orderIndex INTEGER NOT NULL,
            PRIMARY KEY (playlistId, sourceId, itemId),
            FOREIGN KEY (sourceId, playlistId) REFERENCES playlists(sourceId, id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_playlist_items_source_playlist ON playlist_items(sourceId, playlistId)",

        """CREATE TABLE IF NOT EXISTS annotations (
            id TEXT NOT NULL PRIMARY KEY,
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            type TEXT NOT NULL DEFAULT 'HIGHLIGHT',
            cfi TEXT NOT NULL,
            color TEXT NOT NULL DEFAULT 'yellow',
            note TEXT,
            textSnippet TEXT NOT NULL,
            textBefore TEXT NOT NULL DEFAULT '',
            textAfter TEXT NOT NULL DEFAULT '',
            chapterHref TEXT NOT NULL,
            spineIndex INTEGER NOT NULL DEFAULT 0,
            progression REAL NOT NULL DEFAULT 0.0,
            bookmarkTitle TEXT NOT NULL DEFAULT '',
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            originDeviceId TEXT NOT NULL,
            lastModifiedByDeviceId TEXT NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0,
            lastSyncedAt INTEGER NOT NULL DEFAULT 0,
            embeddedFigures TEXT,
            imageHref TEXT,
            imageSvg TEXT,
            imageBytes TEXT,
            originFontFamily TEXT,
            emphasisStyles TEXT,
            textSnippetHtml TEXT,
            fragmentAnchor TEXT,
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_annotations_sourceId_itemId ON annotations(sourceId, itemId)",
    )

    private val LOCAL_FILES_DDL = listOf(
        """CREATE TABLE IF NOT EXISTS local_files_folders (
            sourceId TEXT NOT NULL,
            treeUri TEXT NOT NULL,
            displayName TEXT NOT NULL,
            addedAtEpochMs INTEGER NOT NULL,
            libraryId TEXT NOT NULL,
            PRIMARY KEY (sourceId, treeUri),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_local_files_folders_sourceId ON local_files_folders(sourceId)",

        """CREATE TABLE IF NOT EXISTS local_files_files (
            sourceId TEXT NOT NULL,
            sourceItemId TEXT NOT NULL,
            originalUri TEXT NOT NULL,
            copiedPath TEXT NOT NULL,
            coverPath TEXT,
            format TEXT NOT NULL,
            sizeBytes INTEGER NOT NULL,
            mtimeEpochMs INTEGER NOT NULL,
            lastSeenAtEpochMs INTEGER NOT NULL,
            displayName TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (sourceId, sourceItemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_local_files_files_sourceId ON local_files_files(sourceId)",

        """CREATE TABLE IF NOT EXISTS local_files_file_folders (
            sourceId TEXT NOT NULL,
            sourceItemId TEXT NOT NULL,
            folderTreeUri TEXT NOT NULL,
            lastSeenAtEpochMs INTEGER NOT NULL,
            PRIMARY KEY (sourceId, sourceItemId, folderTreeUri),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_local_files_file_folders_sourceId ON local_files_file_folders(sourceId)",
        "CREATE INDEX IF NOT EXISTS index_local_files_file_folders_folder ON local_files_file_folders(sourceId, folderTreeUri)",
    )

    // v3 -> v4: add tables for reading positions, audiobook positions and per-book formatting.
    // DDL mirrors the Room entities column-for-column (schema v74 / Room entity definitions).
    private val POSITION_AND_PREFS_DDL = listOf(
        """CREATE TABLE IF NOT EXISTS reading_positions (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            cfi TEXT NOT NULL,
            localUpdatedAt INTEGER NOT NULL DEFAULT 0,
            lastSyncedAt INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (sourceId, itemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_reading_positions_sourceId ON reading_positions(sourceId)",

        """CREATE TABLE IF NOT EXISTS audiobook_positions (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            positionSec REAL NOT NULL,
            localUpdatedAt INTEGER NOT NULL DEFAULT 0,
            lastSyncedAt INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (sourceId, itemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_audiobook_positions_sourceId ON audiobook_positions(sourceId)",

        """CREATE TABLE IF NOT EXISTS book_formatting_preferences (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            screenDimensionBucket TEXT NOT NULL,
            fontSize REAL,
            theme TEXT,
            fontFamily TEXT,
            lineSpacing REAL,
            margins REAL,
            orientation TEXT,
            showChapterMap INTEGER,
            coloredChapterMap INTEGER,
            showReadingProgressLabels INTEGER,
            showCurrentChapterLabel INTEGER,
            doublePageSpread INTEGER,
            justifyText INTEGER,
            showReadingTimeEstimate INTEGER,
            PRIMARY KEY (sourceId, itemId, screenDimensionBucket),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_book_formatting_preferences_sourceId ON book_formatting_preferences(sourceId)",

        """CREATE TABLE IF NOT EXISTS book_comic_formatting_preferences (
            source_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            background_theme TEXT,
            panel_view_on INTEGER,
            panel_overflow TEXT,
            panel_animation_speed_ms INTEGER,
            PRIMARY KEY (source_id, item_id),
            FOREIGN KEY (source_id) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_book_comic_formatting_preferences_source_id ON book_comic_formatting_preferences(source_id)",
    )

    // v4 -> v5: add tables for the 14 DAOs that were bound to no-op stubs (issue #1057).
    // DDL mirrors the Room entities column-for-column (schema v74 / Room entity definitions).
    private val FORMERLY_NOOP_DAO_DDL = listOf(
        """CREATE TABLE IF NOT EXISTS readaloud_links (
            absSourceId TEXT NOT NULL,
            absLibraryItemId TEXT NOT NULL,
            storytellerSourceId TEXT NOT NULL,
            storytellerBookId TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'CONFIRMED',
            userConfirmed INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            identityResult TEXT NOT NULL DEFAULT 'UNKNOWN',
            PRIMARY KEY (absSourceId, absLibraryItemId),
            FOREIGN KEY (storytellerSourceId) REFERENCES sources(id) ON DELETE CASCADE,
            FOREIGN KEY (absSourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_readaloud_links_storytellerSourceId_storytellerBookId ON readaloud_links(storytellerSourceId, storytellerBookId)",
        "CREATE INDEX IF NOT EXISTS index_readaloud_links_storytellerSourceId ON readaloud_links(storytellerSourceId)",

        """CREATE TABLE IF NOT EXISTS readaloud_candidates (
            storytellerSourceId TEXT NOT NULL,
            storytellerBookId TEXT NOT NULL,
            absSourceId TEXT NOT NULL,
            absLibraryItemId TEXT NOT NULL,
            score REAL NOT NULL,
            PRIMARY KEY (storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId),
            FOREIGN KEY (storytellerSourceId) REFERENCES sources(id) ON DELETE CASCADE,
            FOREIGN KEY (absSourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_readaloud_candidates_absSourceId ON readaloud_candidates(absSourceId)",

        """CREATE TABLE IF NOT EXISTS readaloud_dismissals (
            storytellerSourceId TEXT NOT NULL,
            storytellerBookId TEXT NOT NULL,
            scope TEXT NOT NULL,
            absSourceId TEXT NOT NULL DEFAULT '',
            absLibraryItemId TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId),
            FOREIGN KEY (storytellerSourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",

        """CREATE TABLE IF NOT EXISTS cross_epub_index (
            absEpubChecksum TEXT NOT NULL,
            storytellerEpubChecksum TEXT NOT NULL,
            perChapterMapsBlob TEXT NOT NULL,
            builtAt INTEGER NOT NULL,
            PRIMARY KEY (absEpubChecksum, storytellerEpubChecksum)
        )""",

        """CREATE TABLE IF NOT EXISTS readaloud_resume_positions (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            href TEXT NOT NULL,
            progression REAL,
            fragmentRef TEXT,
            localUpdatedAt INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (sourceId, itemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_readaloud_resume_positions_sourceId ON readaloud_resume_positions(sourceId)",

        """CREATE TABLE IF NOT EXISTS audio_playback_preferences (
            sourceId TEXT NOT NULL,
            bookId TEXT NOT NULL,
            speed REAL,
            PRIMARY KEY (sourceId, bookId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_audio_playback_preferences_sourceId ON audio_playback_preferences(sourceId)",

        """CREATE TABLE IF NOT EXISTS audiobook_bookmarks (
            id TEXT NOT NULL PRIMARY KEY,
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            positionSec REAL NOT NULL,
            title TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            localUpdatedAt INTEGER NOT NULL DEFAULT 0,
            lastSyncedAt INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_audiobook_bookmarks_sourceId ON audiobook_bookmarks(sourceId)",
        "CREATE INDEX IF NOT EXISTS index_audiobook_bookmarks_sourceId_itemId ON audiobook_bookmarks(sourceId, itemId)",

        """CREATE TABLE IF NOT EXISTS audiobook_chapter_cache (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            chaptersJson TEXT NOT NULL,
            cachedAt INTEGER NOT NULL,
            PRIMARY KEY (sourceId, itemId)
        )""",

        """CREATE TABLE IF NOT EXISTS local_file_metadata_overrides (
            sourceId TEXT NOT NULL,
            sourceItemId TEXT NOT NULL,
            title TEXT,
            author TEXT,
            seriesName TEXT,
            seriesIndex REAL,
            coverUrl TEXT,
            PRIMARY KEY (sourceId, sourceItemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_local_file_metadata_overrides_sourceId ON local_file_metadata_overrides(sourceId)",

        """CREATE TABLE IF NOT EXISTS remote_item_freshness (
            sourceId TEXT NOT NULL,
            sourceItemId TEXT NOT NULL,
            lastFetchedAt INTEGER NOT NULL,
            PRIMARY KEY (sourceId, sourceItemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",

        """CREATE TABLE IF NOT EXISTS publication_metrics_cache (
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            ebookFileIno TEXT NOT NULL,
            totalPositions INTEGER,
            pageCount INTEGER,
            cachedAt INTEGER NOT NULL,
            epubVersion TEXT,
            PRIMARY KEY (sourceId, itemId),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_publication_metrics_cache_sourceId ON publication_metrics_cache(sourceId)",

        """CREATE TABLE IF NOT EXISTS dictionary_packs (
            languageTag TEXT NOT NULL PRIMARY KEY,
            packVersion TEXT NOT NULL,
            installedAt INTEGER NOT NULL,
            sizeBytes INTEGER NOT NULL,
            attributionHtml TEXT NOT NULL,
            licenseUrl TEXT NOT NULL,
            state TEXT NOT NULL
        )""",

        """CREATE TABLE IF NOT EXISTS lookup_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            languageTag TEXT NOT NULL,
            form TEXT NOT NULL,
            lookedUpAt INTEGER NOT NULL
        )""",

        """CREATE TABLE IF NOT EXISTS cover_grid_scale (
            sourceId TEXT NOT NULL,
            libraryId TEXT NOT NULL,
            screenDimensionBucket TEXT NOT NULL,
            scale REAL NOT NULL,
            PRIMARY KEY (sourceId, libraryId, screenDimensionBucket),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_cover_grid_scale_sourceId ON cover_grid_scale(sourceId)",
    )

    // v2 -> v3: rebuild series/collections with the Room-entity shape (see migrate()).
    private val SERIES_COLLECTIONS_REBUILD_DDL = listOf(
        "DROP TABLE IF EXISTS series",
        """CREATE TABLE IF NOT EXISTS series (
            id TEXT NOT NULL PRIMARY KEY,
            libraryId TEXT NOT NULL,
            name TEXT NOT NULL,
            coverUrl TEXT,
            bookCount INTEGER NOT NULL DEFAULT 0
        )""",
        "DROP TABLE IF EXISTS collections",
        """CREATE TABLE IF NOT EXISTS collections (
            id TEXT NOT NULL PRIMARY KEY,
            libraryId TEXT NOT NULL,
            name TEXT NOT NULL,
            bookCount INTEGER NOT NULL DEFAULT 0
        )""",
    )
}
