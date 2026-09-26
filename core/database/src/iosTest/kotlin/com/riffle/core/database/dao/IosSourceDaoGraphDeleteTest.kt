package com.riffle.core.database.dao

import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.database.ReadingPositionEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression for #1101: six of `IosSourceDao`'s per-table source-graph deletes were `= Unit`,
 * so removing a source on iOS left its reading positions, audiobook positions, formatting
 * preferences and local-file records behind (the driver never enables `PRAGMA foreign_keys`, so
 * the schema's `ON DELETE CASCADE` never fired either). Each test seeds the same table for two
 * sources, calls the *specific* delete method, and asserts one source's rows are gone while the
 * other's survive — reverting any single stub to `= Unit` turns its test red.
 */
class IosSourceDaoGraphDeleteTest : IosDaoTestBase() {

    private val folderUri = "file:///folders/a"
    private val otherFolderUri = "file:///folders/b"

    @Test
    fun deleteReadingPositionsForSourceRemovesOnlyThatSourcesRows() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readingPositionDao()
        dao.upsert(ReadingPositionEntity(SOURCE_ID, ITEM_ID, cfi = "epubcfi(/6/4)"))
        dao.upsert(ReadingPositionEntity(OTHER_SOURCE_ID, ITEM_ID, cfi = "epubcfi(/6/8)"))

        db.sourceDao().deleteReadingPositionsForSource(SOURCE_ID)

        assertNull(dao.getByItemId(SOURCE_ID, ITEM_ID))
        assertNotNull(dao.getByItemId(OTHER_SOURCE_ID, ITEM_ID))
    }

    @Test
    fun deleteAudiobookPositionsForSourceRemovesOnlyThatSourcesRows() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.audiobookPositionDao()
        dao.upsert(AudiobookPositionEntity(SOURCE_ID, ITEM_ID, positionSec = 12.5))
        dao.upsert(AudiobookPositionEntity(OTHER_SOURCE_ID, ITEM_ID, positionSec = 99.0))

        db.sourceDao().deleteAudiobookPositionsForSource(SOURCE_ID)

        assertNull(dao.getByItemId(SOURCE_ID, ITEM_ID))
        assertNotNull(dao.getByItemId(OTHER_SOURCE_ID, ITEM_ID))
    }

    @Test
    fun deleteBookFormattingPreferencesForSourceRemovesOnlyThatSourcesRows() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.bookFormattingPreferencesDao()
        val bucket = "Compact_Medium"
        dao.upsert(BookFormattingPreferencesEntity(SOURCE_ID, ITEM_ID, bucket, fontSize = 1.2f))
        dao.upsert(BookFormattingPreferencesEntity(OTHER_SOURCE_ID, ITEM_ID, bucket, fontSize = 0.9f))

        db.sourceDao().deleteBookFormattingPreferencesForSource(SOURCE_ID)

        assertNull(dao.getByItemId(SOURCE_ID, ITEM_ID, bucket))
        assertNotNull(dao.getByItemId(OTHER_SOURCE_ID, ITEM_ID, bucket))
    }

    @Test
    fun deleteLocalFilesTablesForSourceRemoveOnlyThatSourcesRows() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        seedLocalFiles(SOURCE_ID, folderUri)
        seedLocalFiles(OTHER_SOURCE_ID, otherFolderUri)

        val sourceDao = db.sourceDao()
        sourceDao.deleteLocalFilesFileFoldersForSource(SOURCE_ID)
        sourceDao.deleteLocalFilesFilesForSource(SOURCE_ID)
        sourceDao.deleteLocalFilesFoldersForSource(SOURCE_ID)

        assertEquals(emptyList<LocalFilesFileFolderEntity>(), db.localFilesFileFolderDao().forFile(SOURCE_ID, ITEM_ID))
        assertNull(db.localFilesFileDao().findById(SOURCE_ID, ITEM_ID))
        assertEquals(emptyList<LocalFilesFolderEntity>(), db.localFilesFolderDao().forSource(SOURCE_ID))

        assertEquals(1, db.localFilesFileFolderDao().forFile(OTHER_SOURCE_ID, ITEM_ID).size)
        assertNotNull(db.localFilesFileDao().findById(OTHER_SOURCE_ID, ITEM_ID))
        assertEquals(1, db.localFilesFolderDao().forSource(OTHER_SOURCE_ID).size)
    }

    /** End-to-end: the graph delete a source removal runs leaves nothing behind in any of the six tables. */
    @Test
    fun deleteSourceGraphClearsEveryFormerlyOrphanedTable() = runTest {
        seedSource(SOURCE_ID)
        db.readingPositionDao().upsert(ReadingPositionEntity(SOURCE_ID, ITEM_ID, cfi = "epubcfi(/6/4)"))
        db.audiobookPositionDao().upsert(AudiobookPositionEntity(SOURCE_ID, ITEM_ID, positionSec = 1.0))
        db.bookFormattingPreferencesDao().upsert(BookFormattingPreferencesEntity(SOURCE_ID, ITEM_ID, "Compact_Medium"))
        seedLocalFiles(SOURCE_ID, folderUri)

        db.sourceDao().deleteSourceGraph(SOURCE_ID)

        assertNull(db.sourceDao().getById(SOURCE_ID))
        assertNull(db.readingPositionDao().getByItemId(SOURCE_ID, ITEM_ID))
        assertNull(db.audiobookPositionDao().getByItemId(SOURCE_ID, ITEM_ID))
        assertNull(db.bookFormattingPreferencesDao().getByItemId(SOURCE_ID, ITEM_ID, "Compact_Medium"))
        assertEquals(emptyList<LocalFilesFolderEntity>(), db.localFilesFolderDao().forSource(SOURCE_ID))
        assertNull(db.localFilesFileDao().findById(SOURCE_ID, ITEM_ID))
        assertEquals(emptyList<LocalFilesFileFolderEntity>(), db.localFilesFileFolderDao().forFile(SOURCE_ID, ITEM_ID))
    }

    private suspend fun seedLocalFiles(sourceId: String, treeUri: String) {
        db.localFilesFolderDao().upsert(
            LocalFilesFolderEntity(sourceId, treeUri, displayName = "Books", addedAtEpochMs = 1L, libraryId = "lib-$sourceId"),
        )
        db.localFilesFileDao().upsert(
            LocalFilesFileEntity(
                sourceId = sourceId,
                sourceItemId = ITEM_ID,
                originalUri = "$treeUri/book.epub",
                copiedPath = "/copies/$sourceId/book.epub",
                coverPath = null,
                format = "epub",
                sizeBytes = 10L,
                mtimeEpochMs = 1L,
                lastSeenAtEpochMs = 1L,
            ),
        )
        db.localFilesFileFolderDao().upsert(
            LocalFilesFileFolderEntity(sourceId, ITEM_ID, folderTreeUri = treeUri, lastSeenAtEpochMs = 1L),
        )
    }
}
