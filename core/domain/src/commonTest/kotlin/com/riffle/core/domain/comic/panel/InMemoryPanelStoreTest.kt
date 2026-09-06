package com.riffle.core.domain.comic.panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryPanelStoreTest {

    private fun panels(pageIndex: Int) = PagePanels(
        pageIndex = pageIndex,
        imageWidth = 100,
        imageHeight = 100,
        panels = listOf(PanelRegion(0, 0, 100, 100)),
        source = PanelSource.Auto,
    )

    @Test
    fun `save then load returns the page`() {
        val store = InMemoryPanelStore()
        store.save("book1", panels(2))
        assertEquals(2, store.load("book1", 2)?.pageIndex)
    }

    @Test
    fun `load miss returns null`() {
        val store = InMemoryPanelStore()
        assertNull(store.load("book1", 0))
        store.save("book1", panels(0))
        assertNull(store.load("book1", 5))
        assertNull(store.load("otherBook", 0))
    }

    @Test
    fun `saveAll and loadAll round-trip`() {
        val store = InMemoryPanelStore()
        store.saveAll("book1", listOf(panels(0), panels(1), panels(2)))
        assertEquals(setOf(0, 1, 2), store.loadAll("book1").keys)
    }

    @Test
    fun `clear drops a book`() {
        val store = InMemoryPanelStore()
        store.save("book1", panels(0))
        store.clear("book1")
        assertNull(store.load("book1", 0))
        assertEquals(emptyMap(), store.loadAll("book1"))
    }
}
