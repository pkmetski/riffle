package com.riffle.core.data

import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.BookComicFormattingPreferencesEntity
import com.riffle.core.domain.comic.PanelOverflowBehavior
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookComicFormattingPreferencesStoreImplTest {

    private fun storeWith(entity: BookComicFormattingPreferencesEntity?): BookComicFormattingPreferencesStoreImpl {
        val dao = object : BookComicFormattingPreferencesDao {
            override suspend fun upsert(entity: BookComicFormattingPreferencesEntity) {}
            override suspend fun getByItemId(sourceId: String, itemId: String) = entity
            override suspend fun deleteByItemId(sourceId: String, itemId: String) {}
        }
        return BookComicFormattingPreferencesStoreImpl(dao)
    }

    @Test fun `unknown panelOverflow name maps to null — forward-compat guard`() = runTest {
        val entity = BookComicFormattingPreferencesEntity(
            sourceId = "src", itemId = "item",
            panelViewOn = true, panelOverflow = "UNKNOWN_FUTURE_VALUE",
            panelAnimationSpeedMs = null,
        )
        val result = storeWith(entity).overrides("src::item").first()
        assertNull("Unknown enum name must not crash; maps to null", result.panelOverflow)
        assertEquals(true, result.panelViewOn)
    }

    @Test fun `known panelOverflow SPLIT is parsed correctly`() = runTest {
        val entity = BookComicFormattingPreferencesEntity(
            sourceId = "src", itemId = "item",
            panelViewOn = null, panelOverflow = "SPLIT",
            panelAnimationSpeedMs = null,
        )
        val result = storeWith(entity).overrides("src::item").first()
        assertEquals(PanelOverflowBehavior.SPLIT, result.panelOverflow)
        assertNull(result.panelViewOn)
    }

    @Test fun `known panelOverflow SMART_SPLIT is parsed correctly`() = runTest {
        val entity = BookComicFormattingPreferencesEntity(
            sourceId = "src", itemId = "item",
            panelViewOn = true, panelOverflow = "SMART_SPLIT",
            panelAnimationSpeedMs = null,
        )
        val result = storeWith(entity).overrides("src::item").first()
        assertEquals(PanelOverflowBehavior.SMART_SPLIT, result.panelOverflow)
    }

    @Test fun `null entity returns empty overrides`() = runTest {
        val result = storeWith(null).overrides("src::item").first()
        assertNull(result.panelViewOn)
        assertNull(result.panelOverflow)
        assertNull(result.panelAnimationSpeedMs)
    }

    @Test fun `panelAnimationSpeedMs is round-tripped through entity`() = runTest {
        val entity = BookComicFormattingPreferencesEntity(
            sourceId = "src", itemId = "item",
            panelViewOn = true, panelOverflow = null,
            panelAnimationSpeedMs = 400,
        )
        val result = storeWith(entity).overrides("src::item").first()
        assertEquals(400, result.panelAnimationSpeedMs)
    }

    @Test fun `null panelAnimationSpeedMs in entity maps to null override — follows global`() = runTest {
        val entity = BookComicFormattingPreferencesEntity(
            sourceId = "src", itemId = "item",
            panelViewOn = true, panelOverflow = null,
            panelAnimationSpeedMs = null,
        )
        val result = storeWith(entity).overrides("src::item").first()
        assertNull(result.panelAnimationSpeedMs)
    }
}
