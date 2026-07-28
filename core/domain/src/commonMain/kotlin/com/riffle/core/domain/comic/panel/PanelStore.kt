package com.riffle.core.domain.comic.panel

/**
 * Durable per-book cache of detected/resolved panel regions. Keyed by a `bookId` that already
 * baked in the archive's content hash (so a re-packed archive with a different content hash
 * gets a fresh cache).
 *
 * Panel regions for a page are expensive to compute (image decode + detection). Once computed,
 * they are stable for the lifetime of the archive, so we persist them across app restarts.
 */
interface PanelStore {
    fun load(bookId: String, pageIndex: Int): PagePanels?
    fun loadAll(bookId: String): Map<Int, PagePanels>
    fun save(bookId: String, page: PagePanels)
    fun saveAll(bookId: String, pages: Collection<PagePanels>)
    fun clear(bookId: String)
}
