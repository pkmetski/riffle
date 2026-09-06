package com.riffle.core.domain.comic.panel

/**
 * Session-scoped in-memory [PanelStore]. Panel regions are recomputed on the first open of each
 * page and cached for the lifetime of the process. Unlike the Android `JsonPanelStore`, results do
 * not survive an app restart — acceptable because detection is deterministic and re-runs cheaply
 * off the decoded page. Used as the iOS default until an on-disk store is added.
 *
 * Thread-safety mirrors `JsonPanelStore`: concurrent writes for the same book race with last-writer-
 * wins, which is fine because within a reader session all writes flow through a single orchestrator
 * and the result for a page is deterministic.
 */
class InMemoryPanelStore : PanelStore {
    private val byBook = mutableMapOf<String, MutableMap<Int, PagePanels>>()

    override fun load(bookId: String, pageIndex: Int): PagePanels? =
        byBook[bookId]?.get(pageIndex)

    override fun loadAll(bookId: String): Map<Int, PagePanels> =
        byBook[bookId]?.toMap() ?: emptyMap()

    override fun save(bookId: String, page: PagePanels) {
        byBook.getOrPut(bookId) { mutableMapOf() }[page.pageIndex] = page
    }

    override fun saveAll(bookId: String, pages: Collection<PagePanels>) {
        val book = byBook.getOrPut(bookId) { mutableMapOf() }
        pages.forEach { book[it.pageIndex] = it }
    }

    override fun clear(bookId: String) {
        byBook.remove(bookId)
    }
}
