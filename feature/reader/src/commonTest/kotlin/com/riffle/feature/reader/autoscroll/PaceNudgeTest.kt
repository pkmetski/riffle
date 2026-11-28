package com.riffle.feature.reader.autoscroll

import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeStore : BookFormattingPreferencesStore {
    private val rows = mutableMapOf<Triple<String, String, ScreenDimensionBucket>, BookFormattingOverrides>()

    override suspend fun load(
        sourceId: String,
        itemId: String,
        dimension: ScreenDimensionBucket,
    ): BookFormattingOverrides? = rows[Triple(sourceId, itemId, dimension)]

    override suspend fun save(
        sourceId: String,
        itemId: String,
        dimension: ScreenDimensionBucket,
        overrides: BookFormattingOverrides,
    ) {
        rows[Triple(sourceId, itemId, dimension)] = overrides
    }

    override suspend fun clear(sourceId: String, itemId: String, dimension: ScreenDimensionBucket) {
        rows.remove(Triple(sourceId, itemId, dimension))
    }
}

class PaceNudgeTest {
    @Test
    fun nudgeAndPersistSavesToThePerBookStore() = runTest {
        val store = FakeStore()
        val out = nudgeAndPersistPace(store, "src", "item", ScreenDimensionBucket.PhonePortrait, BookFormattingOverrides(), 260) { o, w ->
            o.copy(autoScrollWpm = w)
        }
        assertEquals(260, out?.autoScrollWpm)
        assertEquals(260, store.load("src", "item", ScreenDimensionBucket.PhonePortrait)?.autoScrollWpm)
    }

    @Test
    fun nudgeAndPersistPreservesOtherOverrides() = runTest {
        val store = FakeStore()
        val out = nudgeAndPersistPace(store, "src", "item", ScreenDimensionBucket.PhonePortrait, BookFormattingOverrides(fontSize = 1.4f), 260) { o, w ->
            o.copy(autoScrollWpm = w)
        }
        assertEquals(1.4f, out?.fontSize)
        assertEquals(1.4f, store.load("src", "item", ScreenDimensionBucket.PhonePortrait)?.fontSize)
    }

    @Test
    fun nudgeAndPersistWithNullWpmSavesNothing() = runTest {
        val store = FakeStore()
        assertNull(nudgeAndPersistPace(store, "src", "item", ScreenDimensionBucket.PhonePortrait, BookFormattingOverrides(), null) { o, w ->
            o.copy(autoScrollWpm = w)
        })
        assertNull(store.load("src", "item", ScreenDimensionBucket.PhonePortrait))
    }
}
