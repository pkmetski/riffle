package com.riffle.shared.reader

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelEngine
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource

object IosNoOpPanelEngine : PanelEngine {
    override fun forBook(bookId: String, imageBytes: (Int) -> ByteArray): PanelEngine.Book =
        object : PanelEngine.Book {
            override fun resolvePage(pageIndex: Int) = PagePanels(
                pageIndex = pageIndex,
                imageWidth = 1,
                imageHeight = 1,
                panels = listOf(PanelRegion(0, 0, 1, 1)),
                source = PanelSource.Fallback,
            )
        }
}
