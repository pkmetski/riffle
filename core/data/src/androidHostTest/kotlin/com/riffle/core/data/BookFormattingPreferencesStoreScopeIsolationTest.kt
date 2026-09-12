package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression pins for the book-formatting-preferences key shape. Both the full-book reader and
 * the elided (annotations) reader share one row per (sourceId, itemId, screenDimensionBucket),
 * and each screenDimensionBucket gets an independent row.
 *
 * Removed-test: save under FullBook does not affect read under Highlights
 * Removed-test: save under Highlights does not affect read under FullBook
 * Removed-test: both scopes hold independent values for the same book
 * Removed-test: clear only removes the targeted scope
 *
 * Behaviour retired: per-scope isolation in the book-preferences store no longer exists; the
 * full-book reader and elided reader share one row so per-book customisations propagate to both.
 * The separate FormattingScope-keyed store was added in migration 44→45 and removed in 70→71.
 */
class BookFormattingPreferencesStoreScopeIsolationTest {

    private val sourceId = "srv-A"
    private val dim = ScreenDimensionBucket.PhonePortrait
    private val dimLandscape = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Compact)

    private class InMemoryDao : BookFormattingPreferencesDao {
        val rows = mutableMapOf<Triple<String, String, String>, BookFormattingPreferencesEntity>()
        override suspend fun upsert(entity: BookFormattingPreferencesEntity) {
            rows[Triple(entity.sourceId, entity.itemId, entity.screenDimensionBucket)] = entity
        }
        override suspend fun getByItemId(
            sourceId: String,
            itemId: String,
            screenDimensionBucket: String,
        ): BookFormattingPreferencesEntity? = rows[Triple(sourceId, itemId, screenDimensionBucket)]
        override suspend fun deleteByItemId(
            sourceId: String,
            itemId: String,
            screenDimensionBucket: String,
        ) {
            rows.remove(Triple(sourceId, itemId, screenDimensionBucket))
        }
    }

    private fun newStore(): BookFormattingPreferencesStoreImpl = BookFormattingPreferencesStoreImpl(
        dao = InMemoryDao(),
    )

    @Test
    fun `override round-trips for a book`() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(theme = ReaderTheme.Dark))

        assertEquals(
            ReaderTheme.Dark,
            store.load(sourceId, "item-1", dim)?.theme,
        )
    }

    @Test
    fun `portrait and landscape dimensions hold independent values for the same book`() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(fontSize = 1.4f))
        store.save(sourceId, "item-1", dimLandscape, BookFormattingOverrides(fontSize = 1.8f))

        assertEquals(1.4f, store.load(sourceId, "item-1", dim)?.fontSize)
        assertEquals(1.8f, store.load(sourceId, "item-1", dimLandscape)?.fontSize)
    }

    @Test
    fun `clear removes the row for the targeted dimension only`() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(fontSize = 1.4f))
        store.save(sourceId, "item-1", dimLandscape, BookFormattingOverrides(fontSize = 1.8f))

        store.clear(sourceId, "item-1", dim)

        assertNull(
            "Portrait value must be gone after a portrait-scoped clear",
            store.load(sourceId, "item-1", dim)?.fontSize,
        )
        assertEquals(
            "Landscape value must survive a portrait-scoped clear",
            1.8f,
            store.load(sourceId, "item-1", dimLandscape)?.fontSize,
        )
    }

    @Test
    fun `colored chapter map override round-trips for a book`() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(coloredChapterMap = false))

        assertEquals(
            false,
            store.load(sourceId, "item-1", dim)?.coloredChapterMap,
        )
    }

    @Test
    fun `two sources with the same itemId hold independent settings`() = runTest {
        val store = newStore()
        store.save("src-A", "item-1", dim, BookFormattingOverrides(fontSize = 1.4f))
        store.save("src-B", "item-1", dim, BookFormattingOverrides(fontSize = 1.8f))

        assertEquals(1.4f, store.load("src-A", "item-1", dim)?.fontSize)
        assertEquals(1.8f, store.load("src-B", "item-1", dim)?.fontSize)
    }
}
