package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceDaoDeleteGraphTest {
    private lateinit var db: RiffleDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, RiffleDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteSourceGraphRemovesAbsSourceRowsWithoutForeignKeyFailure() = runTest {
        seedAbsSourceGraph()

        db.sourceDao().deleteSourceGraph(ABS_SOURCE_ID)

        assertEquals(0, count("sources", "id = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("libraries", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("library_items", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("reading_positions", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("book_formatting_preferences", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("annotations", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("playlists", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("playlist_items", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("publication_metrics_cache", "sourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("readaloud_links", "absSourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("readaloud_candidates", "absSourceId = '$ABS_SOURCE_ID'"))
        assertEquals(0, count("readaloud_dismissals", "absSourceId = '$ABS_SOURCE_ID'"))
        assertEquals(1, count("sources", "id = '$OTHER_SOURCE_ID'"))
        assertEquals(1, count("publication_metrics_cache", "sourceId = '$OTHER_SOURCE_ID'"))
    }

    private suspend fun seedAbsSourceGraph() {
        db.sourceDao().upsert(SourceEntity(ABS_SOURCE_ID, "https://abs.example.invalid", true, false, "u"))
        db.sourceDao().upsert(SourceEntity(STORYTELLER_SOURCE_ID, "https://storyteller.example.invalid", false, false, "u", "STORYTELLER_SERVICE"))
        db.sourceDao().upsert(SourceEntity(OTHER_SOURCE_ID, "https://other.example.invalid", false, false, "u"))

        sql("INSERT INTO libraries (id, name, mediaType, sourceId, isUnsupported) VALUES ('lib-1', 'Books', 'book', '$ABS_SOURCE_ID', 0)")
        sql("INSERT INTO library_items (sourceId, id, libraryId, title, author, coverUrl, readingProgress, ebookFormat, hasAudio, audioDurationSec, genres, addedAt) VALUES ('$ABS_SOURCE_ID', 'item-1', 'lib-1', 'Title', 'Author', NULL, 0.25, 'epub', 1, 123.0, '', 10)")
        sql("INSERT INTO reading_positions (sourceId, itemId, cfi, localUpdatedAt, lastSyncedAt) VALUES ('$ABS_SOURCE_ID', 'item-1', 'epubcfi(/6/4!/4/1:0)', 20, 10)")
        sql("INSERT INTO book_formatting_preferences (sourceId, itemId, screenDimensionBucket, fontSize) VALUES ('$ABS_SOURCE_ID', 'item-1', 'Compact_Medium', 1.0)")
        db.annotationDao().upsertAll(
            listOf(
                AnnotationEntity(
                    id = "ann-1",
                    sourceId = ABS_SOURCE_ID,
                    itemId = "item-1",
                    type = AnnotationEntity.TYPE_HIGHLIGHT,
                    cfi = "epubcfi(/6/4!/4/1:0,/1:0,/1:5)",
                    textSnippet = "text",
                    chapterHref = "chapter.xhtml",
                    createdAt = 1,
                    updatedAt = 1,
                    originDeviceId = "device",
                    lastModifiedByDeviceId = "device",
                )
            )
        )
        sql("INSERT INTO playlists (id, sourceId, rootId, name, bookCount) VALUES ('playlist-1', '$ABS_SOURCE_ID', 'lib-1', 'Queue', 1)")
        sql("INSERT INTO playlist_items (playlistId, sourceId, itemId, orderIndex) VALUES ('playlist-1', '$ABS_SOURCE_ID', 'item-1', 0)")
        sql("INSERT INTO publication_metrics_cache (sourceId, itemId, ebookFileIno, totalPositions, pageCount, cachedAt, epubVersion) VALUES ('$ABS_SOURCE_ID', 'item-1', 'ino-1', 100, NULL, 30, '3')")
        sql("INSERT INTO publication_metrics_cache (sourceId, itemId, ebookFileIno, totalPositions, pageCount, cachedAt, epubVersion) VALUES ('$OTHER_SOURCE_ID', 'other-item', 'ino-2', 50, NULL, 30, '3')")
        sql("INSERT INTO readaloud_links (absSourceId, absLibraryItemId, storytellerSourceId, storytellerBookId, state, userConfirmed, createdAt, updatedAt, identityResult) VALUES ('$ABS_SOURCE_ID', 'item-1', '$STORYTELLER_SOURCE_ID', 'st-book-1', '${ReadaloudLinkEntity.STATE_CONFIRMED}', 1, 1, 1, '${ReadaloudLinkEntity.IDENTITY_UNKNOWN}')")
        sql("INSERT INTO readaloud_candidates (storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId, score) VALUES ('$STORYTELLER_SOURCE_ID', 'st-book-1', '$ABS_SOURCE_ID', 'item-1', 0.9)")
        sql("INSERT INTO readaloud_dismissals (storytellerSourceId, storytellerBookId, scope, absSourceId, absLibraryItemId) VALUES ('$STORYTELLER_SOURCE_ID', 'st-book-1', '${ReadaloudDismissalEntity.SCOPE_CANDIDATE}', '$ABS_SOURCE_ID', 'item-1')")
    }

    private fun sql(statement: String) {
        db.openHelper.writableDatabase.execSQL(statement)
    }

    private fun count(table: String, where: String): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val ABS_SOURCE_ID = "abs-1"
        const val STORYTELLER_SOURCE_ID = "storyteller-1"
        const val OTHER_SOURCE_ID = "abs-2"
    }
}
