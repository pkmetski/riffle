package com.riffle.feature.reader

import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelEngine
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CbzReaderViewModelTest {

    private val fullPage = PanelRegion(0, 0, 100, 100)
    private val panel1 = PanelRegion(0, 0, 50, 100)
    private val panel2 = PanelRegion(50, 0, 50, 100)
    private val panel3 = PanelRegion(0, 50, 100, 50)

    private fun fallbackPanels(pageIndex: Int) = PagePanels(
        pageIndex = pageIndex,
        imageWidth = 100,
        imageHeight = 100,
        panels = listOf(fullPage),
        source = PanelSource.Fallback,
    )

    private fun realPanels(pageIndex: Int, vararg regions: PanelRegion) = PagePanels(
        pageIndex = pageIndex,
        imageWidth = 100,
        imageHeight = 100,
        panels = regions.toList(),
        source = PanelSource.Auto,
    )

    private fun makeImageSource(count: Int = 5): ComicImageSource = object : ComicImageSource {
        override val pageCount = count
        override fun imageBytes(pageIndex: Int) = byteArrayOf()
        override fun mediaType(pageIndex: Int) = "image/jpeg"
    }

    private fun noOpEngine(): PanelEngine = object : PanelEngine {
        override fun forBook(bookId: String, imageBytes: (Int) -> ByteArray): PanelEngine.Book =
            object : PanelEngine.Book {
                override fun resolvePage(pageIndex: Int) = fallbackPanels(pageIndex)
            }
    }

    private fun realEngine(pageCount: Int, panelsPerPage: Int = 3): PanelEngine = object : PanelEngine {
        override fun forBook(bookId: String, imageBytes: (Int) -> ByteArray): PanelEngine.Book =
            object : PanelEngine.Book {
                override fun resolvePage(pageIndex: Int) =
                    if (panelsPerPage == 2) realPanels(pageIndex, panel1, panel2)
                    else realPanels(pageIndex, panel1, panel2, panel3)
            }
    }

    private fun makeVm(
        pageCount: Int = 5,
        engine: PanelEngine = noOpEngine(),
        onPositionChanged: (Int) -> Unit = {},
    ) = CbzReaderViewModel(
        imageSource = makeImageSource(pageCount),
        panelEngine = engine,
        bookId = "book1",
        onPositionChanged = onPositionChanged,
    )

    @Test
    fun `initial page is 0`() {
        val vm = makeVm()
        assertEquals(0, vm.currentPage.value)
    }

    @Test
    fun `gotoPage updates currentPage`() {
        val vm = makeVm()
        vm.gotoPage(3)
        assertEquals(3, vm.currentPage.value)
    }

    @Test
    fun `gotoPage clamps to valid range`() {
        val vm = makeVm(pageCount = 5)
        vm.gotoPage(-1)
        assertEquals(0, vm.currentPage.value)
        vm.gotoPage(100)
        assertEquals(4, vm.currentPage.value)
    }

    @Test
    fun `gotoPage fires onPositionChanged`() {
        val fired = mutableListOf<Int>()
        val vm = makeVm(onPositionChanged = { fired += it })
        vm.gotoPage(2)
        assertEquals(listOf(2), fired)
    }

    @Test
    fun `gotoPage does not fire onPositionChanged when page unchanged`() {
        val fired = mutableListOf<Int>()
        val vm = makeVm(onPositionChanged = { fired += it })
        vm.gotoPage(0) // already on page 0
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `panel index starts at -1`() {
        val vm = makeVm()
        assertEquals(-1, vm.currentPanelIndex.value)
    }

    @Test
    fun `nextPanel with fallback panels stays at -1`() {
        val vm = makeVm(engine = noOpEngine())
        vm.nextPanel()
        assertEquals(-1, vm.currentPanelIndex.value)
    }

    @Test
    fun `enterPanelView with real panels sets panelIndex to 0`() {
        val vm = makeVm(engine = realEngine(5))
        vm.enterPanelView()
        assertEquals(0, vm.currentPanelIndex.value)
    }

    @Test
    fun `enterPanelView with fallback panels does not activate panel view`() {
        val vm = makeVm(engine = noOpEngine())
        vm.enterPanelView()
        assertEquals(-1, vm.currentPanelIndex.value)
    }

    @Test
    fun `nextPanel advances panel index`() {
        val vm = makeVm(engine = realEngine(5, panelsPerPage = 2))
        vm.enterPanelView()
        vm.nextPanel()
        assertEquals(1, vm.currentPanelIndex.value)
    }

    @Test
    fun `nextPanel at last panel goes to next page`() {
        val fired = mutableListOf<Int>()
        val vm = makeVm(pageCount = 5, engine = realEngine(5, panelsPerPage = 2), onPositionChanged = { fired += it })
        vm.enterPanelView() // panelIndex = 0
        vm.nextPanel()      // panelIndex = 1 (last panel of 2)
        vm.nextPanel()      // should advance to page 1, panel 0
        assertEquals(1, vm.currentPage.value)
        assertEquals(0, vm.currentPanelIndex.value)
        assertEquals(listOf(1), fired)
    }

    @Test
    fun `prevPanel at first panel goes to prev page last panel`() {
        val vm = makeVm(pageCount = 5, engine = realEngine(5, panelsPerPage = 2))
        vm.gotoPage(1)
        vm.enterPanelView() // panelIndex = 0
        vm.prevPanel()      // should go to page 0, last panel (index 1 of 2)
        assertEquals(0, vm.currentPage.value)
        assertEquals(1, vm.currentPanelIndex.value)
    }

    @Test
    fun `exitPanelView resets panelIndex to -1`() {
        val vm = makeVm(engine = realEngine(5))
        vm.enterPanelView()
        vm.exitPanelView()
        assertEquals(-1, vm.currentPanelIndex.value)
    }

    @Test
    fun `pageCount matches imageSource`() {
        val vm = makeVm(pageCount = 7)
        assertEquals(7, vm.pageCount)
    }
}
