package com.riffle.app.feature.reader

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the cross-session bookmark indicator.
 *
 * The reader's CornerBookmarkIndicator is driven by [com.riffle.app.feature.reader.EpubReaderViewModel.isCurrentPageBookmarked],
 * which compares the live navigator's locator href against the stored chapterHref. Readium serves
 * the EPUB via a localhost HTTP server with a port chosen per session, so a raw `==` comparison
 * silently misses a bookmark created in a prior session even though the chapter path is identical.
 *
 * The fix normalizes both sides via [normalizeEpubHref]; this test pins that behaviour using
 * AnnotationStoreImpl against a real Room database, then replays the actual matching predicate the
 * ViewModel uses. Works the same in paginated, vertical, and continuous modes because all three
 * funnel through the same predicate.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkIndicatorReopenInstrumentedTest {

    private lateinit var db: RiffleDatabase
    private lateinit var store: AnnotationStoreImpl

    private class FixedDeviceIdStore : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-test"
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = AnnotationStoreImpl(
            dao = db.annotationDao(),
            deviceIdStore = FixedDeviceIdStore(),
            clock = { 1_000L },
            idGenerator = { "uuid-1" },
        )
        runBlocking {
            db.serverDao().upsert(
                ServerEntity("abs1", "http://abs1", isActive = true, insecureConnectionAllowed = false, username = "u"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bookmark_lights_up_on_reopen_when_localhost_port_differs() = runTest {
        // Session A: bookmark stored against this session's Readium localhost URL.
        val sessionAHref = "http://localhost:41234/OEBPS/chapter1.xhtml"
        store.createBookmark(
            serverId = "abs1",
            itemId = "item-1",
            cfi = "epubcfi(/6/2!/4/2/1:0)",
            textSnippet = "",
            chapterHref = sessionAHref,
            spineIndex = 0,
            progression = 0.30,
            bookmarkTitle = "Test",
        )

        // Session B: same chapter, different port.
        val reopened = store.observeBookmarks("abs1", "item-1").first()
        assertEquals(1, reopened.size)
        val positions = reopened.map {
            BookmarkPosition(it.id, normalizeEpubHref(it.chapterHref), it.progression)
        }

        val sessionBHref = "http://localhost:58901/OEBPS/chapter1.xhtml"
        assertTrue(
            "Same-chapter bookmark must light up across sessions despite differing localhost ports",
            indicatorFires(positions, sessionBHref, currentProgression = 0.31f),
        )
    }

    @Test
    fun bookmark_does_not_light_up_on_different_chapter() = runTest {
        val sessionAHref = "http://localhost:41234/OEBPS/chapter1.xhtml"
        store.createBookmark(
            serverId = "abs1",
            itemId = "item-1",
            cfi = "epubcfi(/6/2!/4/2/1:0)",
            textSnippet = "",
            chapterHref = sessionAHref,
            spineIndex = 0,
            progression = 0.30,
            bookmarkTitle = "Test",
        )

        val reopened = store.observeBookmarks("abs1", "item-1").first()
        val positions = reopened.map {
            BookmarkPosition(it.id, normalizeEpubHref(it.chapterHref), it.progression)
        }

        val differentChapterHref = "http://localhost:58901/OEBPS/chapter2.xhtml"
        assertFalse(
            "Indicator must not fire on a different chapter even with matching progression",
            indicatorFires(positions, differentChapterHref, currentProgression = 0.30f),
        )
    }

    @Test
    fun bookmark_does_not_light_up_outside_progression_window() = runTest {
        store.createBookmark(
            serverId = "abs1",
            itemId = "item-1",
            cfi = "epubcfi(/6/2!/4/2/1:0)",
            textSnippet = "",
            chapterHref = "http://localhost:41234/OEBPS/chapter1.xhtml",
            spineIndex = 0,
            progression = 0.10,
            bookmarkTitle = "Test",
        )

        val reopened = store.observeBookmarks("abs1", "item-1").first()
        val positions = reopened.map {
            BookmarkPosition(it.id, normalizeEpubHref(it.chapterHref), it.progression)
        }

        // Outside ±5% window.
        assertFalse(
            indicatorFires(positions, "http://localhost:58901/OEBPS/chapter1.xhtml", currentProgression = 0.30f),
        )
    }

    private data class BookmarkPosition(val id: String, val chapterHref: String, val progression: Double)

    // Mirrors EpubReaderViewModel.isCurrentPageBookmarked's predicate.
    private fun indicatorFires(
        positions: List<BookmarkPosition>,
        currentHref: String,
        currentProgression: Float?,
    ): Boolean {
        val hrefNorm = normalizeEpubHref(currentHref)
        return positions.any { bm ->
            bm.chapterHref == hrefNorm &&
                (currentProgression == null || kotlin.math.abs(bm.progression - currentProgression) < 0.05)
        }
    }
}
