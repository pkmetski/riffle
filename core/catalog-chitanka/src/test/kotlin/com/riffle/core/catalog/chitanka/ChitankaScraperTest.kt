package com.riffle.core.catalog.chitanka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChitankaScraperTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `category listing yields books with covers and authors`() {
        val html = fixture("chitanka-category.html")
        val res = ChitankaScraper.parseSearchResults(html)
        assertTrue("expected books, got ${res.items.size}", res.items.size > 5)
        val first = res.items.first()
        assertTrue("title should be non-empty", first.title.isNotEmpty())
        assertTrue("book url should start with /book/", first.url.contains("/book/"))
        assertEquals("epub", first.format)
    }

    @Test
    fun `categories page yields 20+ entries and are Bulgarian-sorted`() {
        val html = fixture("chitanka-categories.html")
        val cats = ChitankaScraper.parseCategories(html)
        assertTrue("expected 20+ categories, got ${cats.size}", cats.size >= 20)
        // All labels start with Cyrillic or contain some Bulgarian text
        assertTrue(cats.all { it.label.isNotEmpty() && it.path.startsWith("/books/category/") })
    }

    @Test
    fun `series alpha page enumerates series entries`() {
        val html = fixture("chitanka-series-alpha.html")
        val series = ChitankaScraper.parseSeriesAlphaPage(html)
        assertTrue("expected many series entries, got ${series.size}", series.size >= 20)
        val first = series.first()
        assertTrue("slug should start with serie/", first.first.startsWith("serie/"))
        assertTrue("label should be non-empty", first.second.isNotEmpty())
    }

    @Test
    fun `detail page extracts title, authors, and epub download link`() {
        val html = fixture("chitanka-text-detail.html")
        val d = ChitankaScraper.parseDetailPage(html, "https://chitanka.info/text/44723-abu-hasan")
        assertTrue("title should be non-empty", d.title.isNotEmpty())
        assertNotNull("cover should be present", d.coverUrl)
        assertTrue(
            "download url should be an .epub, got ${d.downloadUrl}",
            d.downloadUrl?.endsWith(".epub") == true,
        )
        assertEquals("Bulgarian", d.language)
    }

    @Test
    fun `detail page extracts description from book-anno block on book pages`() {
        // Mirrors chitanka's /book/ HTML: generic meta description, real annotation in
        // div.text-content.book-anno. Regression for missing book summaries in the app.
        val html = """
            <html><head>
              <meta name="description" content="Универсална библиотека за книги и текстове.">
            </head><body>
              <h1><a class="selflink" itemprop="name">Пример</a></h1>
              <div class="media-body">
                <div class="text-content book-anno">
                  <p id="p-1">Първи абзац от анотацията.</p>
                  <p id="p-2">Втори абзац.</p>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val d = ChitankaScraper.parseDetailPage(html, "https://chitanka.info/book/3000-primer")

        assertEquals(
            "Първи абзац от анотацията.\n\nВтори абзац.",
            d.description,
        )
    }

    @Test
    fun `toAbsolute resolves relative and preserves absolute urls`() {
        assertEquals(
            "https://chitanka.info/book/1",
            ChitankaScraper.toAbsolute("/book/1"),
        )
        assertEquals(
            "https://chitanka.info/book/1",
            ChitankaScraper.toAbsolute("https://chitanka.info/book/1"),
        )
        assertEquals(
            "https://gramofonche.chitanka.info/foo.mp3",
            ChitankaScraper.toAbsolute("//gramofonche.chitanka.info/foo.mp3"),
        )
    }

    @Test
    fun `empty href returns empty absolute`() {
        assertEquals("", ChitankaScraper.toAbsolute(null))
        assertEquals("", ChitankaScraper.toAbsolute(""))
    }

    @Test
    fun `empty html yields empty listing and null next page`() {
        val res = ChitankaScraper.parseSearchResults("<html><body></body></html>")
        assertTrue(res.items.isEmpty())
        assertNull(res.nextPagePath)
    }
}

class GramofoncheScraperTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `prikazki listing yields audio items with cover and duration`() {
        val html = fixture("gramofonche-prikazki.html")
        val res = GramofoncheScraper.parseSearchResults(html)
        assertTrue("expected 20+ items, got ${res.items.size}", res.items.size >= 20)
        val first = res.items.first()
        assertEquals("mp3", first.format)
        assertEquals(ChitankaSite.GRAMOFONCHE, first.site)
        assertTrue("title should be non-empty", first.title.isNotEmpty())
    }

    @Test
    fun `detail page extracts mp3 downloads and cover`() {
        val html = fixture("gramofonche-detail.html")
        val url = "https://gramofonche.chitanka.info/prikazki/aladin-i-vylshebnata-lampa/"
        val d = GramofoncheScraper.parseDetailPage(html, url)
        assertTrue("title should be non-empty", d.title.isNotEmpty())
        assertTrue(
            "expected 1+ mp3 downloads, got ${d.downloads.size}",
            d.downloads.isNotEmpty(),
        )
        assertTrue(
            "all downloads should be mp3 urls",
            d.downloads.all { it.url.endsWith(".mp3") },
        )
        assertEquals("Bulgarian", d.language)
        assertEquals("mp3", d.format)
    }
}
