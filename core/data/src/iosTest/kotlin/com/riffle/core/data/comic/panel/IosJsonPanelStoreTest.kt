package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun samplePanel(x: Int = 0, y: Int = 0) = PanelRegion(x = x, y = y, width = 100, height = 100)
private fun samplePagePanels(pageIndex: Int, vararg panels: PanelRegion = arrayOf(samplePanel())) =
    PagePanels(pageIndex = pageIndex, imageWidth = 400, imageHeight = 600, panels = panels.toList(), source = PanelSource.Auto)

@OptIn(ExperimentalForeignApi::class)
class IosJsonPanelStoreTest {

    private val tmpRoots = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        tmpRoots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private fun makeStore(): IosJsonPanelStore {
        val dir = NSTemporaryDirectory() + "panel_store_test_" + NSUUID().UUIDString()
        tmpRoots += dir
        return IosJsonPanelStore(dir)
    }

    @Test
    fun load_returnsNull_whenNothingSaved() {
        val store = makeStore()
        assertNull(store.load("book-1", 0))
    }

    @Test
    fun save_andLoad_roundTrips() {
        val store = makeStore()
        val page = samplePagePanels(3)
        store.save("book-1", page)
        val loaded = store.load("book-1", 3)
        assertEquals(page, loaded)
    }

    @Test
    fun save_multiplePages_preservesAll() {
        val store = makeStore()
        store.save("book-1", samplePagePanels(0))
        store.save("book-1", samplePagePanels(1))
        store.save("book-1", samplePagePanels(5))
        val all = store.loadAll("book-1")
        assertEquals(3, all.size)
        assertTrue(0 in all)
        assertTrue(1 in all)
        assertTrue(5 in all)
    }

    @Test
    fun save_overwrites_existingPage() {
        val store = makeStore()
        store.save("book-1", samplePagePanels(2, samplePanel(x = 0)))
        store.save("book-1", samplePagePanels(2, samplePanel(x = 100)))
        val loaded = store.load("book-1", 2)!!
        assertEquals(100, loaded.panels[0].x)
    }

    @Test
    fun saveAll_mergesWithExisting() {
        val store = makeStore()
        store.save("book-1", samplePagePanels(0))
        store.saveAll("book-1", listOf(samplePagePanels(1), samplePagePanels(2)))
        val all = store.loadAll("book-1")
        assertEquals(3, all.size)
    }

    @Test
    fun clear_removesAllPages() {
        val store = makeStore()
        store.save("book-1", samplePagePanels(0))
        store.save("book-1", samplePagePanels(1))
        store.clear("book-1")
        assertTrue(store.loadAll("book-1").isEmpty())
    }

    @Test
    fun isolatesBooksByBookId() {
        val store = makeStore()
        store.save("book-1", samplePagePanels(0, samplePanel(x = 10)))
        store.save("book-2", samplePagePanels(0, samplePanel(x = 99)))
        assertEquals(10, store.load("book-1", 0)!!.panels[0].x)
        assertEquals(99, store.load("book-2", 0)!!.panels[0].x)
    }

    @Test
    fun loadAll_returnsEmpty_forUnknownBook() {
        val store = makeStore()
        assertTrue(store.loadAll("nonexistent").isEmpty())
    }

    @Test
    fun survivesAppRestart_viaNewInstance() {
        val dir = NSTemporaryDirectory() + "panel_store_restart_" + NSUUID().UUIDString()
        tmpRoots += dir
        val store1 = IosJsonPanelStore(dir)
        store1.save("book-x", samplePagePanels(7))
        val store2 = IosJsonPanelStore(dir)
        val loaded = store2.load("book-x", 7)
        assertEquals(samplePagePanels(7), loaded)
    }
}
