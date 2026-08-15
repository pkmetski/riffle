package com.riffle.app.feature.source.websource

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedItemMatcherTest {

    private fun serverItem(
        title: String,
        author: String = "",
        isbn: String? = null,
    ) = LibraryItem(
        id = "server-${title.take(4)}",
        libraryId = "lib-1",
        title = title,
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        isbn = isbn,
    )

    private fun catalogItem(
        title: String,
        author: String = "",
        isbn: String? = null,
    ) = CatalogItem(
        id = "cat-${title.take(4)}",
        rootId = "root",
        title = title,
        author = author,
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
        isbn = isbn,
    )

    private fun index(vararg items: LibraryItem) = buildOwnedItemIndex(items.toList())

    // ─── normalizeTitle ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `normalizeTitle lowercases`() {
        assertEquals("аладин", normalizeTitle("Аладин"))
    }

    @Test
    fun `normalizeTitle replaces hyphens with spaces`() {
        assertEquals("абу мързеливият", normalizeTitle("Абу-мързеливият"))
    }

    @Test
    fun `normalizeTitle replaces en-dash and em-dash with spaces`() {
        assertEquals("абу мързеливият", normalizeTitle("Абу–мързеливият"))
        assertEquals("абу мързеливият", normalizeTitle("Абу—мързеливият"))
    }

    @Test
    fun `normalizeTitle strips punctuation`() {
        assertEquals("аладин и вълшебната лампа роман", normalizeTitle("Аладин и вълшебната лампа (роман)"))
    }

    @Test
    fun `normalizeTitle collapses whitespace`() {
        assertEquals("аладин и лампа", normalizeTitle("Аладин  и   лампа"))
    }

    // ─── Empty / trivial cases ───────────────────────────────────────────────────────────────────

    @Test
    fun `empty index means nothing is owned`() {
        val item = catalogItem("The Hobbit", "Tolkien")
        assertFalse(index().isOwned(item))
    }

    // ─── Exact title+author ──────────────────────────────────────────────────────────────────────

    @Test
    fun `exact title+author match`() {
        val idx = index(serverItem("Басни", "Лафонтен"))
        assertTrue(idx.isOwned(catalogItem("Басни", "Лафонтен")))
    }

    @Test
    fun `no false positive on same title different author`() {
        val idx = index(serverItem("Басни", "Лафонтен"))
        assertFalse(idx.isOwned(catalogItem("Басни", "Алберт Декало")))
    }

    // ─── Title-only fallback ─────────────────────────────────────────────────────────────────────

    @Test
    fun `title-only match when ABS item has no author`() {
        val idx = index(serverItem("Аладин", ""))
        assertTrue(idx.isOwned(catalogItem("Аладин", "Some Author")))
    }

    @Test
    fun `title-only match when catalog item has no author`() {
        val idx = index(serverItem("Аладин", ""))
        assertTrue(idx.isOwned(catalogItem("Аладин", "")))
    }

    // ─── Hyphen normalisation ────────────────────────────────────────────────────────────────────

    @Test
    fun `hyphenated candidate matches space-separated ABS title`() {
        val idx = index(serverItem("Абу мързеливият и хубавицата", "Шехерезада"))
        assertTrue(idx.isOwned(catalogItem("Абу-мързеливият и хубавицата", "Шехерезада")))
    }

    @Test
    fun `space-separated candidate matches hyphenated ABS title`() {
        val idx = index(serverItem("Абу-мързеливият и хубавицата", "Шехерезада"))
        assertTrue(idx.isOwned(catalogItem("Абу мързеливият и хубавицата", "Шехерезада")))
    }

    // ─── Fuzzy author (spaceless + containment) ──────────────────────────────────────────────────

    @Test
    fun `matches when ABS author has role prefix and no spaces`() {
        val idx = index(serverItem("Котаракът в чизми", "реж.ЛилянаТодорова ШарлПеро"))
        assertTrue(idx.isOwned(catalogItem("Котаракът в чизми", "Шарл Перо")))
    }

    @Test
    fun `no false positive on completely unrelated authors`() {
        val idx = index(serverItem("Котаракът в чизми", "реж.ЛилянаТодорова ШарлПеро"))
        assertFalse(idx.isOwned(catalogItem("Котаракът в чизми", "Иван Вазов")))
    }

    // ─── Prefix matching ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `matches when ABS title has extra suffix metadata and authors agree`() {
        val idx = index(serverItem("Винету и Поразяващата ръка :К.Май :БалканТон", "Карл Май"))
        assertTrue(idx.isOwned(catalogItem("Винету и Поразяващата ръка", "Карл Май")))
    }

    @Test
    fun `matches when ABS title has suffix and author is concatenated without spaces`() {
        val idx = index(serverItem(
            "Вълшебното камъче :АфриканскаПриказка,реж.В.Чачановски :БалканТон",
            "реж.ВихрониЧачановски АфриканскаПриказка",
        ))
        assertTrue(idx.isOwned(catalogItem("Вълшебното камъче", "Африканска Приказка")))
    }

    @Test
    fun `no prefix match for short candidate titles (under 8 chars)`() {
        val idx = index(serverItem("Басни и разкази", "Лафонтен"))
        assertFalse(idx.isOwned(catalogItem("Басни", "Лафонтен")))
    }

    @Test
    fun `no prefix match when authors differ even if title prefix matches`() {
        val idx = index(serverItem("Винету и Поразяващата ръка :К.Май :БалканТон", "Карл Май"))
        assertFalse(idx.isOwned(catalogItem("Винету и Поразяващата ръка", "Иван Вазов")))
    }

    @Test
    fun `matches when ABS title has suffix metadata and no author field (title-only prefix)`() {
        // ABS upload stored "Клан, клан-недоклан :НароднаПриказка :БалканТон" with author=""
        // Strategy 5 fails (authorsOverlap against "" rejects); strategy 5b should catch it.
        val idx = index(serverItem("Клан, клан-недоклан :НароднаПриказка :БалканТон", ""))
        assertTrue(idx.isOwned(catalogItem("Клан, клан-недоклан", "Народна Приказка")))
    }

    @Test
    fun `no title-only prefix match for short candidate titles (under 8 chars)`() {
        val idx = index(serverItem("Клан :НароднаПриказка :БалканТон", ""))
        assertFalse(idx.isOwned(catalogItem("Клан", "Народна Приказка")))
    }

    // ─── Author order inversion (Gramofonche "Author, реж. Director" vs ABS "реж.Director Author") ─

    @Test
    fun `matches when candidate has Author+role and ABS has role+Director+Author concatenated`() {
        // Gramofonche: "Братя Грим, реж. Мария Нанчева"
        // ABS upload:  "реж.МарияНанчева БратяГрим"  (role prefix first, no inner spaces)
        val idx = index(serverItem("Веселите градски музиканти", "реж.МарияНанчева БратяГрим"))
        assertTrue(idx.isOwned(catalogItem("Веселите градски музиканти", "Братя Грим, реж. Мария Нанчева")))
    }

    @Test
    fun `matches inverted order for Шехерезада author`() {
        val idx = index(serverItem("Аладин и вълшебната лампа", "реж.МарияНанчева Шехерезада"))
        assertTrue(idx.isOwned(catalogItem("Аладин и вълшебната лампа", "Шехерезада, реж. Мария Нанчева")))
    }

    @Test
    fun `no false positive on inverted-order check — different author, same director`() {
        val idx = index(serverItem("Аладин и вълшебната лампа", "реж.МарияНанчева Шехерезада"))
        assertFalse(idx.isOwned(catalogItem("Аладин и вълшебната лампа", "Братя Грим, реж. Мария Нанчева")))
    }

    // ─── No false positives on partial title overlap ──────────────────────────────────────────────

    @Test
    fun `does NOT match series name against a specific volume in ABS (no author)`() {
        val idx = index(serverItem("Приказните светове на Николай Райнов Книга 7", ""))
        assertFalse(idx.isOwned(catalogItem("Приказните светове на Николай Райнов", "")))
    }

    @Test
    fun `does NOT match specific volume against series name in ABS`() {
        val idx = index(serverItem("Приказните светове на Николай Райнов", ""))
        assertFalse(idx.isOwned(catalogItem("Приказните светове на Николай Райнов Книга 7", "")))
    }

    // ─── Fuzzy title ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fuzzy title matches small spelling variation with author confirmation`() {
        val idx = index(serverItem("Маруф обущарят", "Мария Нанчева"))
        assertTrue(idx.isOwned(catalogItem("Маруф обушарт", "реж. Мария Нанчева")))
    }

    @Test
    fun `fuzzy title no match when title difference is too large`() {
        val idx = index(serverItem("Котаракът в чизми", "Шарл Перо"))
        assertFalse(idx.isOwned(catalogItem("Аладин и вълшебната лампа", "Шарл Перо")))
    }

    @Test
    fun `fuzzy title no match when authors do not overlap`() {
        val idx = index(serverItem("Маруф обущарят", "Иван Вазов"))
        assertFalse(idx.isOwned(catalogItem("Маруф обушарт", "реж. Мария Нанчева")))
    }

    // ─── ISBN ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `isbn match wins even when titles differ`() {
        val isbn = "9780061120084"
        val idx = index(serverItem("To Kill a Mockingbird", "Harper Lee", isbn = isbn))
        assertTrue(idx.isOwned(catalogItem("To Kill a Mockingbird (50th Anniversary Edition)", "Lee, Harper", isbn = isbn)))
    }

    @Test
    fun `isbn normalization strips hyphens and ignores case`() {
        val idx = index(serverItem("Book", "Author", isbn = "978-0-06-112008-4"))
        assertTrue(idx.isOwned(catalogItem("Book", "Author", isbn = "9780061120084")))
    }

    @Test
    fun `isbn normalization handles uppercase X in isbn-10`() {
        val idx = index(serverItem("Book", "Author", isbn = "0-674-83777-X"))
        assertTrue(idx.isOwned(catalogItem("Book", "Author", isbn = "067483777x")))
    }

    @Test
    fun `item with isbn falls back to title+author if isbn not in index`() {
        val idx = index(serverItem("Dune", "Frank Herbert"))
        assertTrue(idx.isOwned(catalogItem("Dune", "Frank Herbert", isbn = "9780441013593")))
    }

    // ─── Multiple items ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiple server items build combined index`() {
        val idx = index(
            serverItem("Dune", "Frank Herbert"),
            serverItem("Foundation", "Isaac Asimov"),
        )
        assertTrue(idx.isOwned(catalogItem("Dune", "Frank Herbert")))
        assertTrue(idx.isOwned(catalogItem("Foundation", "Isaac Asimov")))
        assertFalse(idx.isOwned(catalogItem("Neuromancer", "William Gibson")))
    }
}
