package com.riffle.core.database

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

internal object IosRiffleDatabaseSchema : SqlSchema<QueryResult.Value<Unit>> {
    // Tracks the iOS schema version independently of the Android Room schema version.
    // Bumped only when the iOS-side DDL changes; Android Room migrations are irrelevant here.
    override val version: Long = 1L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        DDL.forEach { driver.execute(null, it, 0) }
        return QueryResult.Value(Unit)
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        // No iOS migrations yet — schema debuted at version 1.
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
            PRIMARY KEY (sourceId, id),
            FOREIGN KEY (sourceId) REFERENCES sources(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS index_library_items_sourceId ON library_items(sourceId)",

        // Needed for observeUngroupedByLibraryId subquery filter
        """CREATE TABLE IF NOT EXISTS series (
            id TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            libraryId TEXT NOT NULL,
            name TEXT NOT NULL,
            PRIMARY KEY (sourceId, id)
        )""",
        """CREATE TABLE IF NOT EXISTS series_items (
            seriesId TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            itemId TEXT NOT NULL,
            sequenceOrder REAL,
            PRIMARY KEY (seriesId, sourceId, itemId)
        )""",

        // Needed for observeUngroupedByLibraryId subquery filter
        """CREATE TABLE IF NOT EXISTS collections (
            id TEXT NOT NULL,
            sourceId TEXT NOT NULL,
            libraryId TEXT NOT NULL,
            name TEXT NOT NULL,
            PRIMARY KEY (sourceId, id)
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
}
