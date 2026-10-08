package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression pins for the book-formatting-preferences key shape. Both the full-book reader and
 * the elided (annotations) reader share one row per (sourceId, itemId, screenDimensionBucket),
 * and each screenDimensionBucket gets an independent row.
 *
 * Covers the iOS code path as well as Android — `BookFormattingPreferencesStoreImpl` is in
 * commonMain and this test runs as `commonTest` (iosSimulatorArm64Test on CI).
 */
class BookFormattingPreferencesStoreImplTest {

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
    fun overrideRoundTripsForABook() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(theme = ReaderTheme.Dark))

        assertEquals(ReaderTheme.Dark, store.load(sourceId, "item-1", dim)?.theme)
    }

    @Test
    fun portraitAndLandscapeDimensionsHoldIndependentValues() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(fontSize = 1.4f))
        store.save(sourceId, "item-1", dimLandscape, BookFormattingOverrides(fontSize = 1.8f))

        assertEquals(1.4f, store.load(sourceId, "item-1", dim)?.fontSize)
        assertEquals(1.8f, store.load(sourceId, "item-1", dimLandscape)?.fontSize)
    }

    @Test
    fun clearRemovesOnlyTheTargetedDimension() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(fontSize = 1.4f))
        store.save(sourceId, "item-1", dimLandscape, BookFormattingOverrides(fontSize = 1.8f))

        store.clear(sourceId, "item-1", dim)

        assertNull(store.load(sourceId, "item-1", dim)?.fontSize, "Portrait value must be gone after a portrait-scoped clear")
        assertEquals(1.8f, store.load(sourceId, "item-1", dimLandscape)?.fontSize, "Landscape value must survive a portrait-scoped clear")
    }

    @Test
    fun coloredChapterMapOverrideRoundTrips() = runTest {
        val store = newStore()
        store.save(sourceId, "item-1", dim, BookFormattingOverrides(coloredChapterMap = false))

        assertEquals(false, store.load(sourceId, "item-1", dim)?.coloredChapterMap)
    }

    @Test
    fun twoSourcesWithSameItemIdHoldIndependentSettings() = runTest {
        val store = newStore()
        store.save("src-A", "item-1", dim, BookFormattingOverrides(fontSize = 1.4f))
        store.save("src-B", "item-1", dim, BookFormattingOverrides(fontSize = 1.8f))

        assertEquals(1.4f, store.load("src-A", "item-1", dim)?.fontSize)
        assertEquals(1.8f, store.load("src-B", "item-1", dim)?.fontSize)
    }
}
