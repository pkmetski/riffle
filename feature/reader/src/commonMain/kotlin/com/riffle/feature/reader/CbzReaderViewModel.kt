package com.riffle.feature.reader

import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CbzReaderViewModel(
    private val imageSource: ComicImageSource,
    private val panelEngine: PanelEngine,
    private val bookId: String,
    private val onPositionChanged: (pageIndex: Int) -> Unit,
) {
    val pageCount: Int = imageSource.pageCount

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _currentPanelIndex = MutableStateFlow(-1)
    val currentPanelIndex: StateFlow<Int> = _currentPanelIndex.asStateFlow()

    private val panelBook: PanelEngine.Book = panelEngine.forBook(bookId) { pageIndex ->
        imageSource.imageBytes(pageIndex)
    }

    fun resolvePanels(pageIndex: Int): PagePanels = panelBook.resolvePage(pageIndex)

    fun gotoPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (_currentPage.value != clamped) {
            _currentPage.value = clamped
            _currentPanelIndex.value = -1
            onPositionChanged(clamped)
        }
    }

    fun enterPanelView() {
        val panels = panelBook.resolvePage(_currentPage.value)
        if (!panels.isFallback) {
            _currentPanelIndex.value = 0
        }
    }

    fun exitPanelView() {
        _currentPanelIndex.value = -1
    }

    fun nextPanel() {
        val panelIdx = _currentPanelIndex.value
        if (panelIdx < 0) return
        val panels = panelBook.resolvePage(_currentPage.value)
        if (panels.isFallback) {
            _currentPanelIndex.value = -1
            return
        }
        if (panelIdx < panels.panels.size - 1) {
            _currentPanelIndex.value = panelIdx + 1
        } else {
            val nextPage = _currentPage.value + 1
            if (nextPage < pageCount) {
                _currentPage.value = nextPage
                _currentPanelIndex.value = 0
                onPositionChanged(nextPage)
            }
        }
    }

    fun prevPanel() {
        val panelIdx = _currentPanelIndex.value
        if (panelIdx < 0) return
        if (panelIdx > 0) {
            _currentPanelIndex.value = panelIdx - 1
        } else {
            val prevPage = _currentPage.value - 1
            if (prevPage >= 0) {
                val prevPanels = panelBook.resolvePage(prevPage)
                _currentPage.value = prevPage
                _currentPanelIndex.value = if (prevPanels.isFallback) -1 else prevPanels.panels.size - 1
                onPositionChanged(prevPage)
            }
        }
    }
}
