package com.riffle.core.catalog.chitanka

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

/**
 * Returns the element's text with `<br>` treated as a line break. Bulgarian catalog pages
 * separate metadata fields with `<br>`, and jsoup's `.text()` collapses them into a single
 * space, which lets `[^\n]+` regexes greedily swallow every following field.
 */
private fun textWithLineBreaks(el: Element): String {
    val clone = el.clone()
    clone.select("br").forEach { it.replaceWith(TextNode("\n")) }
    return clone.wholeText()
}

/**
 * Kotlin/jsoup port of `lib/scraper/chitanka.ts` from the reference
 * [chitanka-to-audiobookshelf](https://github.com/pkmetski/chitanka-to-audiobookshelf). Pure
 * parsing — no network, no I/O — every method takes raw HTML in and returns typed data out.
 * All CSS selectors and DOM idioms mirror the TypeScript/cheerio implementation 1:1.
 */
internal object ChitankaScraper {

    const val BASE = "https://chitanka.info"

    /** Resolves relative hrefs against [BASE]; percent-encodes non-ASCII as needed. */
    internal fun toAbsolute(href: String?, pageUrl: String = "$BASE/"): String {
        if (href.isNullOrEmpty()) return ""
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        return try {
            URI(pageUrl).resolve(href).toASCIIString()
        } catch (_: Exception) {
            "$BASE${if (href.startsWith("/")) href else "/$href"}"
        }
    }

    fun parseSearchResults(html: String): ChitankaListingResult {
        val doc: Document = Jsoup.parse(html)
        val items = mutableListOf<ChitankaBookSummary>()
        val slugCoverMap = mutableMapOf<String, String>()

        // 1) "Книги" section: article.book-media inside div.booklist. Add books directly and
        // build a slug→cover map used to enrich sibling text results.
        doc.select("div.booklist article.book-media").forEach { el ->
            val bookLink = el.selectFirst("a.booklink") ?: return@forEach
            val href = bookLink.attr("href")
            val url = toAbsolute(href)
            val title = el.selectFirst("[itemprop=name]")?.text()?.trim().orEmpty()
            val imgSrc = el.selectFirst("img[itemprop=image]")?.attr("src")
            val coverUrl = if (!imgSrc.isNullOrEmpty()) toAbsolute(imgSrc) else null
            val slug = href.replace(Regex("^/book/\\d+-"), "")
            if (title.isNotEmpty() && href.isNotEmpty()) {
                items += ChitankaBookSummary(
                    site = ChitankaSite.CHITANKA,
                    url = url,
                    title = title,
                    authors = emptyList(),
                    coverUrl = coverUrl,
                    format = "epub",
                )
                if (slug.isNotEmpty() && coverUrl != null) slugCoverMap[slug] = coverUrl
            }
        }

        // 2) Text results: <li class="title …"> inside ul.superlist.fa-ul.
        doc.select("ul.superlist.fa-ul li.title").forEach { el ->
            val titleEl = el.selectFirst("a.textlink") ?: return@forEach
            val title = titleEl.text().trim()
            val href = titleEl.attr("href")
            val url = toAbsolute(href)

            val authors = el.select("dd.tauthor [itemprop=name]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }

            val textSlug = href.replace(Regex("^/text/\\d+-"), "")
            val coverUrl = slugCoverMap[textSlug]

            if (title.isNotEmpty() && href.isNotEmpty()) {
                items += ChitankaBookSummary(
                    site = ChitankaSite.CHITANKA,
                    url = url,
                    title = title,
                    authors = authors,
                    coverUrl = coverUrl,
                    format = "epub",
                )
            }
        }

        // 3) Fallback for genre/category pages: article.book-media at top level with no superlist.
        if (items.isEmpty()) {
            doc.select("article.book-media").forEach { el ->
                val bookLink = el.selectFirst("a.booklink") ?: return@forEach
                val href = bookLink.attr("href")
                val titleEl = el.selectFirst("[itemprop=name]")
                val title = titleEl?.text()?.trim().orEmpty().ifEmpty {
                    bookLink.attr("title").trim()
                }
                val imgSrc = el.selectFirst("img[itemprop=image]")?.attr("src")
                val coverUrl = if (!imgSrc.isNullOrEmpty()) toAbsolute(imgSrc) else null

                val authors = el.select("[itemprop=author]")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    items += ChitankaBookSummary(
                        site = ChitankaSite.CHITANKA,
                        url = toAbsolute(href),
                        title = title,
                        authors = authors,
                        coverUrl = coverUrl,
                        format = "epub",
                    )
                }
            }
        }

        // Next page: chitanka puts class="next" on the <li>, not the <a>.
        val nextHref = doc.selectFirst("a[rel=next]")?.attr("href")
            ?: doc.selectFirst("li.next a")?.attr("href")

        return ChitankaListingResult(items = items, nextPagePath = nextHref?.ifEmpty { null })
    }

    fun parseDetailPage(html: String, pageUrl: String): ChitankaDetail {
        val doc: Document = Jsoup.parse(html)

        // Text pages:  h1 > span.text-title > a
        // Book pages:  h1 > a.selflink
        val title = doc.selectFirst("h1 span.text-title a")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("h1 span.text-title")?.text()?.trim().orEmpty() }
            .ifEmpty { doc.selectFirst("h1 a.selflink")?.text()?.trim().orEmpty() }

        val authors = doc.select("h1 span[itemtype='http://schema.org/Person'] a[itemprop=name]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        val translators = doc.select("dd.ttranslator [itemprop=name]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        // Description: try meta[name=description], then the book-anno block (present on /book/
        // pages as `div.text-content.book-anno` — the canonical chitanka annotation), then the
        // book-card popover, then the wiki intro.
        val genericMetaPrefix = "Универсална библиотека"
        val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        val bookAnnoDesc = doc.select("div.text-content.book-anno p")
            .joinToString("\n\n") { it.text().trim() }
            .trim()
        val popoverHtml = doc.selectFirst("h4.book-title .popover-trigger")?.attr("data-content").orEmpty()
        val popoverDesc = if (popoverHtml.isNotEmpty() && !popoverHtml.contains("blockquote")) {
            Jsoup.parse(popoverHtml).selectFirst("p")?.text()?.trim().orEmpty()
        } else ""
        val description = when {
            metaDesc.isNotEmpty() && !metaDesc.startsWith(genericMetaPrefix) -> metaDesc
            bookAnnoDesc.isNotEmpty() -> bookAnnoDesc
            popoverDesc.isNotEmpty() -> popoverDesc
            else -> doc.selectFirst("section[data-mw-section-id=0] p")?.text()?.trim().orEmpty()
        }

        val genres = doc.select("ul.simplelist a[href^=/texts/label/]")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .toMutableList()
        val form = doc.selectFirst("dd a[href^=/texts/type/]")?.text()?.trim().orEmpty()
        if (form.isNotEmpty() && form !in genres) genres += form

        val year = doc.selectFirst("[itemprop=datePublished]")?.text()?.trim().orEmpty()

        // Series: <dt>Серия</dt> <dd> <a href="/serie/..."> <i>name</i> </a> (N) </dd>
        var series: ChitankaSeriesRef? = null
        doc.select("dl.dl-horizontal dt").forEach { dt ->
            if (dt.text().trim() == "Серия") {
                val dd = dt.nextElementSibling()
                if (dd != null && dd.tagName() == "dd") {
                    val name = dd.selectFirst("a[href^=/serie/] i")?.text()?.trim()
                        ?: dd.selectFirst("a[href^=/serie/]")?.text()?.trim().orEmpty()
                    val seq = Regex("\\((\\d+)\\)").find(dd.text())?.groupValues?.get(1).orEmpty()
                    if (name.isNotEmpty()) series = ChitankaSeriesRef(name = name, sequence = seq)
                }
            }
        }

        val imgSrc = doc.selectFirst("[itemprop=image]")?.attr("src")
        val coverUrl = if (!imgSrc.isNullOrEmpty()) toAbsolute(imgSrc, pageUrl) else null

        val epubHref = doc.selectFirst("a[href\$=.epub]")?.attr("href").orEmpty()
        val downloadUrl = if (epubHref.isNotEmpty()) toAbsolute(epubHref, pageUrl) else null

        return ChitankaDetail(
            site = ChitankaSite.CHITANKA,
            url = pageUrl,
            title = title,
            authors = authors,
            translators = translators,
            description = description,
            genres = genres,
            language = "Bulgarian",
            year = year,
            series = series,
            coverUrl = coverUrl,
            downloadUrl = downloadUrl,
            format = "epub",
        )
    }

    /** Parses `/books/category` — the top-level category index. Sorted by Bulgarian collation. */
    fun parseCategories(html: String): List<ChitankaCategoryEntry> {
        val doc = Jsoup.parse(html)
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<ChitankaCategoryEntry>()
        doc.select("a[href^=/books/category/]").forEach { el ->
            val path = el.attr("href")
            val label = el.text().trim()
            if (path.isEmpty() || label.isEmpty() || label.startsWith("@")) return@forEach
            if (path in seen) return@forEach
            seen += path
            entries += ChitankaCategoryEntry(label = label, path = path)
        }
        val collator = java.text.Collator.getInstance(java.util.Locale("bg"))
        return entries.sortedWith(compareBy(collator) { it.label })
    }

    /**
     * Parses a `/series/alpha/{letter}` page. Returns pairs (slug, label) where slug is the
     * `/serie/...` path relative to BASE (with leading slash stripped).
     */
    fun parseSeriesAlphaPage(html: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Pair<String, String>>()
        // Each entry: <dt> <a href="/serie/foo" itemprop="name"> <em>Name</em> </a> </dt>
        doc.select("a[href^=/serie/][itemprop=name]").forEach { el ->
            val slug = el.attr("href").trimStart('/')
            val label = el.selectFirst("em")?.text()?.trim() ?: el.text().trim()
            if (slug.isNotEmpty() && label.isNotEmpty()) out += slug to label
        }
        return out
    }
}

/**
 * Kotlin/jsoup port of `lib/scraper/gramofonche.ts`. Same 1:1 fidelity as [ChitankaScraper].
 */
internal object GramofoncheScraper {

    const val BASE = "https://gramofonche.chitanka.info"

    private val CATEGORY_PATH_PATTERN = Regex("^/(prikazki|pesnicki|zagolemi)/[^/]+/$")

    fun parseSearchResults(html: String): ChitankaListingResult {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<ChitankaBookSummary>()

        doc.select("#content-wrapper > div").forEach { el ->
            val linkEl = el.select("a[href]").firstOrNull { a ->
                CATEGORY_PATH_PATTERN.matches(a.attr("href"))
            } ?: return@forEach

            val href = linkEl.attr("href")
            val url = ChitankaScraper.toAbsolute(href, "$BASE/")

            // Text excluding the <i>-wrapped subtitle
            val clone = linkEl.clone()
            clone.select("i").remove()
            val fullText = clone.text().trim()
            val title = fullText.split(Regex("\\s*[(/]"))[0].trim()

            val authors = mutableListOf<String>()
            val iText = linkEl.selectFirst("i")?.text()?.trim().orEmpty()
            if (iText.isNotEmpty()) {
                iText.split(",").forEach { part ->
                    part.trim().takeIf { it.isNotEmpty() }?.let(authors::add)
                }
            }

            val imgSrc = linkEl.selectFirst("img")?.attr("src")
            val coverUrl = if (!imgSrc.isNullOrEmpty())
                ChitankaScraper.toAbsolute(imgSrc, "$BASE/") else null

            val elText = el.text()
            val duration = Regex("(\\d+)мин").find(elText)?.let { "${it.groupValues[1]}мин" }

            if (title.isNotEmpty() && href.isNotEmpty()) {
                items += ChitankaBookSummary(
                    site = ChitankaSite.GRAMOFONCHE,
                    url = url,
                    title = title,
                    authors = authors,
                    coverUrl = coverUrl,
                    format = "mp3",
                    duration = duration,
                )
            }
        }

        val nextHref = doc.selectFirst("a[rel=next]")?.attr("href")
            ?: doc.selectFirst("a.next")?.attr("href")

        return ChitankaListingResult(items = items, nextPagePath = nextHref?.ifEmpty { null })
    }

    fun parseDetailPage(html: String, pageUrl: String): ChitankaDetail {
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("#content-wrapper h1")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("h1")?.text()?.trim().orEmpty() }

        val contentText = doc.selectFirst("#content-wrapper")?.let(::textWithLineBreaks).orEmpty()

        val authors = mutableListOf<String>()
        Regex("автор:\\s*([^\\n]+)").find(contentText)?.let { m ->
            m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(authors::add)
        }

        val narrators = mutableListOf<String>()
        doc.select("blockquote").forEach { el ->
            val bqText = textWithLineBreaks(el)
            Regex("изпълнение:\\s*([^\\n]+)").find(bqText)?.let { m ->
                m.groupValues[1].split(",").forEach { part ->
                    part.trim().takeIf { it.isNotEmpty() }?.let(narrators::add)
                }
            }
        }

        val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        val description = metaDesc.ifEmpty {
            doc.selectFirst("blockquote")?.text()?.trim().orEmpty()
        }

        val year = Regex("година:\\s*(\\d{4})").find(contentText)?.groupValues?.get(1).orEmpty()
        val duration = Regex("(\\d+)мин").find(contentText)?.let { "${it.groupValues[1]}мин" }.orEmpty()

        val imgSrc = doc.selectFirst("div.kolona_kartinki img")?.attr("src")
        val coverUrl = if (!imgSrc.isNullOrEmpty())
            ChitankaScraper.toAbsolute(imgSrc, pageUrl) else null

        val downloads = mutableListOf<ChitankaDownload>()
        doc.select("a[href\$=.mp3]").forEach { el ->
            val href = el.attr("href").ifEmpty { return@forEach }
            val clone = el.clone()
            clone.select("i").remove()
            val trackTitle = clone.text().replace(Regex("\\s*\\(.*$"), "").trim()
            downloads += ChitankaDownload(url = ChitankaScraper.toAbsolute(href, pageUrl), title = trackTitle)
        }

        // Fall back to zip if no MP3 links.
        if (downloads.isEmpty()) {
            val zipHref = doc.selectFirst("div.kolona0 a[href\$=.zip]")?.attr("href")
                ?: doc.selectFirst("a[href\$=.zip]")?.attr("href").orEmpty()
            if (zipHref.isNotEmpty()) {
                val url = ChitankaScraper.toAbsolute(zipHref, pageUrl)
                downloads += ChitankaDownload(
                    url = url,
                    title = zipHref.substringAfterLast('/').removeSuffix(".zip").ifEmpty { "download" },
                )
            }
        }

        return ChitankaDetail(
            site = ChitankaSite.GRAMOFONCHE,
            url = pageUrl,
            title = title,
            authors = authors,
            translators = emptyList(),
            description = description,
            genres = emptyList(),
            language = "Bulgarian",
            year = year,
            series = null,
            coverUrl = coverUrl,
            downloadUrl = null,
            format = "mp3",
            narrators = narrators,
            duration = duration,
            downloads = downloads,
        )
    }
}
