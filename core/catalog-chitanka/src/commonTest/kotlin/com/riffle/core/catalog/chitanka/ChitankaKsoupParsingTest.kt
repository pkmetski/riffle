package com.riffle.core.catalog.chitanka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives real HTML through the scrapers on every target. The fixture-backed suite in `jvmTest`
 * is far more thorough, but it only ever runs on the JVM — and the JVM is the one platform where
 * a DOM difference could not bite, because the scrapers used to be written against jsoup there.
 *
 * These parses are the assertion that ksoup resolves the same selectors, the same attribute
 * lookups and the same `wholeText()`/`<br>` handling on Kotlin/Native as jsoup did on the JVM.
 * The HTML is inlined rather than loaded from a resource because `commonTest` has no portable
 * resource loader.
 */
class ChitankaKsoupParsingTest {

    @Test
    fun searchResultsPromoteTextEntriesAndInheritCoversFromTheBookSection() {
        val html = """
            <html><body>
              <div class="booklist">
                <article class="book-media">
                  <a class="booklink" href="/book/3186-abu-hasan"></a>
                  <span itemprop="name">Абу Хасан</span>
                  <img itemprop="image" src="/thumb/book-cover/31/3186.150.jpg">
                </article>
                <article class="book-media">
                  <a class="booklink" href="/book/1494-bez-korica"></a>
                  <span itemprop="name">Без корица</span>
                  <img itemprop="image" src="/thumb/book-cover/00/0.150.png">
                </article>
              </div>
              <ul class="superlist fa-ul">
                <li class="title">
                  <a class="textlink" href="/text/44723-abu-hasan">Абу Хасан</a>
                  <dd class="tauthor"><span itemprop="name">Шехерезада</span></dd>
                </li>
              </ul>
              <ul class="pagination"><li class="next"><a href="/search?page=2">напред</a></li></ul>
            </body></html>
        """.trimIndent()

        val result = ChitankaScraper.parseSearchResults(html)

        assertEquals(2, result.items.size, "one text entry plus the book card with no text twin")

        val text = result.items[0]
        assertEquals("https://chitanka.info/text/44723-abu-hasan", text.url)
        assertEquals("Абу Хасан", text.title)
        assertEquals(listOf("Шехерезада"), text.authors)
        assertEquals(
            "https://chitanka.info/thumb/book-cover/31/3186.150.jpg",
            text.coverUrl,
            "the /text/ entry must inherit the cover from the matching /book/ card",
        )
        assertEquals("epub", text.format)
        assertEquals(ChitankaSite.CHITANKA, text.site)

        val bookOnly = result.items[1]
        assertEquals("https://chitanka.info/book/1494-bez-korica", bookOnly.url)
        assertNull(bookOnly.coverUrl, "Chitanka's book-cover/00/0.* sentinel must not become a cover URL")

        assertEquals("/search?page=2", result.nextPagePath)
    }

    @Test
    fun chitankaDetailPageExtractsTitleAuthorsCoverAndEpubLink() {
        val html = """
            <html><head>
              <meta name="description" content="Универсална библиотека за книги и текстове.">
            </head><body>
              <h1>
                <span class="text-title"><a href="/text/44723-abu-hasan">Абу Хасан</a></span>
                <span itemtype="http://schema.org/Person"><a itemprop="name" href="/person/1">Шехерезада</a></span>
              </h1>
              <img itemprop="image" src="/thumb/book-cover/31/3186.150.jpg">
              <div class="text-content book-anno"><p>Първи абзац.</p><p>Втори абзац.</p></div>
              <dl class="dl-horizontal">
                <dt>Серия</dt>
                <dd><a href="/serie/1001-nosht"><i>Хиляда и една нощ</i></a> (3)</dd>
              </dl>
              <span itemprop="datePublished">1979</span>
              <a href="/text/44723-abu-hasan.epub">EPUB</a>
            </body></html>
        """.trimIndent()

        val detail = ChitankaScraper.parseDetailPage(html, "https://chitanka.info/text/44723-abu-hasan")

        assertEquals("Абу Хасан", detail.title)
        assertEquals(listOf("Шехерезада"), detail.authors)
        // The generic meta description must lose to the book-anno block.
        assertEquals("Първи абзац.\n\nВтори абзац.", detail.description)
        assertEquals("https://chitanka.info/thumb/book-cover/31/3186.150.jpg", detail.coverUrl)
        assertEquals("https://chitanka.info/text/44723-abu-hasan.epub", detail.downloadUrl)
        assertEquals("1979", detail.year)
        assertEquals(ChitankaSeriesRef(name = "Хиляда и една нощ", sequence = "3"), detail.series)
        assertEquals("Bulgarian", detail.language)
    }

    @Test
    fun categoryIndexIsDeduplicatedAndBulgarianSorted() {
        val html = """
            <html><body>
              <a href="/books/category/humor">Хумор</a>
              <a href="/books/category/drama">Драма</a>
              <a href="/books/category/humor">Хумор</a>
              <a href="/books/category/bg-lit">Българска литература</a>
              <a href="/books/category/skip">@служебна</a>
              <a href="/texts/label/poetry">Поезия</a>
            </body></html>
        """.trimIndent()

        val categories = ChitankaScraper.parseCategories(html)

        assertEquals(
            listOf("Българска литература", "Драма", "Хумор"),
            categories.map { it.label },
            "duplicates and @-prefixed labels drop out; the rest sort in Bulgarian alphabet order",
        )
        assertEquals(
            listOf("/books/category/bg-lit", "/books/category/drama", "/books/category/humor"),
            categories.map { it.path },
        )
    }

    @Test
    fun seriesAlphaPageEnumeratesSlugAndLabel() {
        val html = """
            <html><body><dl>
              <dt><a href="/serie/1001-nosht" itemprop="name"><em>Хиляда и една нощ</em></a></dt>
              <dt><a href="/serie/dyuna" itemprop="name"><em>Дюн</em></a></dt>
            </dl></body></html>
        """.trimIndent()

        assertEquals(
            listOf("serie/1001-nosht" to "Хиляда и една нощ", "serie/dyuna" to "Дюн"),
            ChitankaScraper.parseSeriesAlphaPage(html),
        )
    }

    @Test
    fun gramofoncheDetailPageSplitsBrSeparatedMetadataAndMp3Anchors() {
        val pageUrl = "https://gramofonche.chitanka.info/prikazki/aladin/"
        val html = """
            <html><head><meta name="description" content="Приказка за деца."></head><body>
              <div id="content-wrapper">
                <h1>Аладин и вълшебната лампа</h1>
                <div class="kolona_kartinki"><img src="./cover.jpg"></div>
                автор: Шехерезада<br>
                година: 1979<br>
                размер: 22M ..43мин<br>
                <blockquote>изпълнение: Георги Кадурин, Мария Нанчева<br>режисьор: Х</blockquote>
                <a href="./01-Aladin.mp3">Аладин и вълшебната лампа (Шехерезада)</a>
                <a href="./02-Klan.mp3">Клан-недоклан/Балкантон</a>
              </div>
            </body></html>
        """.trimIndent()

        val detail = GramofoncheScraper.parseDetailPage(html, pageUrl)

        assertEquals("Аладин и вълшебната лампа", detail.title)
        // `<br>` must terminate each field — with jsoup's/ksoup's plain .text() the `[^\n]+`
        // captures swallow every following field into the author and narrator lists.
        assertEquals(listOf("Шехерезада"), detail.authors)
        assertEquals(listOf("Георги Кадурин", "Мария Нанчева"), detail.narrators)
        assertEquals("Приказка за деца.", detail.description)
        assertEquals("1979", detail.year)
        assertEquals("43мин", detail.duration)
        assertEquals("https://gramofonche.chitanka.info/prikazki/aladin/cover.jpg", detail.coverUrl)
        assertEquals("mp3", detail.format)
        assertEquals(ChitankaSite.GRAMOFONCHE, detail.site)

        assertEquals(
            listOf(
                "https://gramofonche.chitanka.info/prikazki/aladin/01-Aladin.mp3",
                "https://gramofonche.chitanka.info/prikazki/aladin/02-Klan.mp3",
            ),
            detail.downloads.map { it.url },
        )
        assertEquals(
            listOf("Аладин и вълшебната лампа", "Клан-недоклан"),
            detail.downloads.map { it.title },
            "track titles drop the narrator parenthetical and the /label suffix",
        )
    }

    @Test
    fun gramofoncheListingExtractsTitleAuthorCoverAndDuration() {
        val html = """
            <html><body><div id="content-wrapper">
              <div>
                <a href="/prikazki/aladin/">
                  <img src="./aladin.jpg">
                  Аладин и вълшебната лампа (реж. Мария Нанчева)
                  <i>Шехерезада, Балкантон</i>
                </a>
                ..43мин
              </div>
              <div><a href="/za-sajta/">За сайта</a></div>
            </div></body></html>
        """.trimIndent()

        val result = GramofoncheScraper.parseSearchResults(html)

        assertEquals(1, result.items.size, "only /prikazki|pesnicki|zagolemi/<slug>/ links are books")
        val item = result.items.single()
        assertEquals("https://gramofonche.chitanka.info/prikazki/aladin/", item.url)
        assertEquals("Аладин и вълшебната лампа", item.title)
        assertEquals(listOf("Шехерезада", "Балкантон"), item.authors)
        assertEquals("https://gramofonche.chitanka.info/aladin.jpg", item.coverUrl)
        assertEquals("43мин", item.duration)
        assertEquals("mp3", item.format)
    }
}
