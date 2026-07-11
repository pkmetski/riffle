package com.riffle.core.catalog.chitanka

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogSeries
import com.riffle.core.catalog.CatalogSeriesEntry
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.domain.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * The Chitanka-backed [Catalog]. Serves two Libraries — Books (chitanka.info) and Audiobooks
 * (gramofonche.chitanka.info) — from a single per-Source instance.
 *
 * Anonymous read-only public HTML — no credentials, no persistent server-side state. All I/O
 * routes through [ChitankaHttpClient] which enforces the 3-attempt 429 retry and identifies the
 * caller with the standard User-Agent. Parsing is delegated to [ChitankaScraper] / [GramofoncheScraper]
 * (pure functions, module-internal types); this class translates those into `core:catalog` vocabulary.
 *
 * Capability shape (ADR 0042):
 * - Mandatory core: implemented.
 * - [SeriesCapability]: yes for `books`; empty list for `audiobooks`.
 * - [AudiobookMediaCapability]: yes for `gramofonche` item ids; no-audiobook (null / empty)
 *   for `chitanka` item ids.
 * - [OfflineBrowseCapability]: marker mixin — this class does not itself do the caching;
 *   a wrapper repository layer will (Slice 9, follow-up PR).
 * - Absent: Collections, Playlists, Progress, ReadingSessions, Stats, Bookmarks.
 */
class ChitankaCatalog(
    private val http: ChitankaHttpClient,
    private val bytesClient: OkHttpClient = OkHttpClient(),
) : Catalog, SeriesCapability, AudiobookMediaCapability, OfflineBrowseCapability {

    override val sourceType: SourceType = SourceType.CHITANKA

    // ---- Roots --------------------------------------------------------------

    override suspend fun listRoots(): List<CatalogRoot> = listOf(
        CatalogRoot(id = ROOT_BOOKS, name = "Chitanka", mediaType = "book"),
        CatalogRoot(id = ROOT_AUDIOBOOKS, name = "gramofonche", mediaType = "audiobook"),
    )

    // ---- Facets -------------------------------------------------------------

    /**
     * Category chip strip contents:
     *  - Books: top-level `/books/category` entries + the two editorial "collections"
     *    (`school`, `university`) surfaced as extra chips per ADR 0042.
     *  - Audiobooks: the three Gramofonche categories (hardcoded — they haven't changed in years).
     *
     * The chip labels come straight from the site in Bulgarian. Sort is Bulgarian collation.
     */
    override suspend fun listFacets(rootId: String): List<CatalogFacet> = when (rootId) {
        ROOT_BOOKS -> booksFacets()
        ROOT_AUDIOBOOKS -> AUDIO_FACETS
        else -> emptyList()
    }

    private suspend fun booksFacets(): List<CatalogFacet> {
        // Session-scoped in-memory cache: the /books/category page changes weekly at most and
        // ChitankaBrowseViewModel calls listFacets() every screen entry. Without this, repeated
        // drawer→Books navigation re-scrapes ~40 categories over the network on every entry —
        // and if the request fails (runCatching-swallowed in the ViewModel), the chip strip
        // goes empty even though the previous entry populated it. ADR 0042 spec'd a 7-day
        // Room cache; this is the strictly-smaller in-process interim.
        cachedBooksFacets?.let { return it }
        val html = http.getString("${ChitankaScraper.BASE}/books/category")
        val entries = ChitankaScraper.parseCategories(html)
        val cats = entries.mapIndexed { idx, e ->
            // key is the last path segment, e.g. "balgarska-fantastika"
            CatalogFacet(
                key = "cat:${e.path.removePrefix("/books/category/")}",
                label = e.label,
                sortOrder = 100 + idx,
            )
        }
        // Editorial "collections" per ADR 0042: two extra chips at the tail.
        val result = cats + EDITORIAL_COLLECTIONS
        cachedBooksFacets = result
        return result
    }

    @Volatile private var cachedBooksFacets: List<CatalogFacet>? = null

    // ---- Browse -------------------------------------------------------------

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        val url = browseUrlFor(rootId, facet, page) ?: return emptyList()
        val html = http.getString(url)
        val listing = when (rootId) {
            ROOT_BOOKS -> ChitankaScraper.parseSearchResults(html)
            ROOT_AUDIOBOOKS -> GramofoncheScraper.parseSearchResults(html)
            else -> return emptyList()
        }
        return listing.items.map { it.toCatalogItem(rootId) }.take(pageSize)
    }

    internal fun browseUrlFor(rootId: String, facet: FacetSelection?, page: Int): String? {
        val pageQuery = if (page > 0) "?page=${page + 1}" else ""
        return when (rootId) {
            ROOT_BOOKS -> {
                val base = when {
                    facet == null -> "${ChitankaScraper.BASE}/new"
                    facet.key.startsWith("cat:") ->
                        "${ChitankaScraper.BASE}/books/category/${facet.key.removePrefix("cat:")}"
                    facet.key == "collection:school" -> "${ChitankaScraper.BASE}/collections/school"
                    facet.key == "collection:university" -> "${ChitankaScraper.BASE}/collections/university"
                    else -> "${ChitankaScraper.BASE}/new"
                }
                base + pageQuery
            }
            ROOT_AUDIOBOOKS -> {
                val bucket = facet?.key ?: "prikazki"
                "${GramofoncheScraper.BASE}/$bucket/" + pageQuery
            }
            else -> null
        }
    }

    // ---- Search -------------------------------------------------------------

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        if (query.isBlank()) return emptyList()
        return when (rootId) {
            ROOT_BOOKS -> {
                val url = "${ChitankaScraper.BASE}/search?q=" + URLEncoder.encode(query, "UTF-8")
                val html = http.getString(url)
                ChitankaScraper.parseSearchResults(html).items
                    .map { it.toCatalogItem(rootId) }
                    .drop(page * pageSize)
                    .take(pageSize)
            }
            ROOT_AUDIOBOOKS -> {
                // Gramofonche has no server-side search — fetch all three category first-pages
                // and filter client-side (per the reference impl).
                val q = query.trim().lowercase()
                val all = coroutineScope {
                    listOf("prikazki", "pesnicki", "zagolemi").map { cat ->
                        async(Dispatchers.IO) {
                            val html = http.getString("${GramofoncheScraper.BASE}/$cat/")
                            GramofoncheScraper.parseSearchResults(html).items
                        }
                    }.awaitAll().flatten()
                }
                all.filter {
                    it.title.lowercase().contains(q) ||
                        it.authors.any { a -> a.lowercase().contains(q) }
                }.map { it.toCatalogItem(rootId) }
                    .drop(page * pageSize)
                    .take(pageSize)
            }
            else -> emptyList()
        }
    }

    // ---- Item lookup --------------------------------------------------------

    override suspend fun getItem(itemId: String): CatalogItem? {
        val (root, detail) = resolveItem(itemId) ?: return null
        val summary = ChitankaBookSummary(
            site = if (root == ROOT_BOOKS) ChitankaSite.CHITANKA else ChitankaSite.GRAMOFONCHE,
            url = detail.url,
            title = detail.title,
            authors = detail.authors.ifEmpty { detail.narrators },
            coverUrl = detail.coverUrl,
            format = detail.format,
            duration = detail.duration.ifEmpty { null },
        )
        // Layer detail-only fields onto the summary conversion.
        val base = summary.toCatalogItem(root)
        return base.copy(
            description = detail.description.ifEmpty { null },
            seriesName = detail.series?.name,
            seriesSequence = detail.series?.sequence?.ifEmpty { null },
            publishedYear = detail.year.ifEmpty { null },
            genres = detail.genres,
            language = detail.language,
        )
    }

    /**
     * Fetches the detail page for [itemId] and returns the parsed [ChitankaDetail].
     * Canonicalises `/book/N-slug` to `/text/N-slug` when the two point at the same book
     * — the `/text/` surface carries the EPUB link + genre labels (ADR 0042).
     */
    private suspend fun resolveItem(itemId: String): Pair<String, ChitankaDetail>? {
        return when {
            itemId.startsWith("text/") -> {
                val url = "${ChitankaScraper.BASE}/$itemId"
                val html = http.getString(url)
                val detail = ChitankaScraper.parseDetailPage(html, url)
                // If genres are empty and we're on a book-side surface, promote to /text/
                ROOT_BOOKS to detail
            }
            itemId.startsWith("book/") -> {
                val url = "${ChitankaScraper.BASE}/$itemId"
                val html = http.getString(url)
                val bookDetail = ChitankaScraper.parseDetailPage(html, url)
                // Try to canonicalise to the /text/ surface for genres.
                val doc = org.jsoup.Jsoup.parse(html)
                val textHref = doc.select("a[href^=/text/]")
                    .map { it.attr("href") }
                    .firstOrNull { !it.contains(".") }
                if (textHref != null) {
                    val textUrl = ChitankaScraper.toAbsolute(textHref)
                    val textHtml = runCatching { http.getString(textUrl) }.getOrNull()
                    if (textHtml != null) {
                        val enriched = ChitankaScraper.parseDetailPage(textHtml, textUrl)
                        // The /text/ surface has no book-anno block — preserve the /book/
                        // annotation (and cover) if the enrichment came back empty.
                        return ROOT_BOOKS to enriched.copy(
                            url = url,  // keep the original id
                            description = enriched.description.ifEmpty { bookDetail.description },
                            coverUrl = enriched.coverUrl ?: bookDetail.coverUrl,
                        )
                    }
                }
                ROOT_BOOKS to bookDetail
            }
            itemId.startsWith("prikazki/") ||
                itemId.startsWith("pesnicki/") ||
                itemId.startsWith("zagolemi/") -> {
                val url = "${GramofoncheScraper.BASE}/$itemId/"
                val html = http.getString(url)
                val detail = GramofoncheScraper.parseDetailPage(html, url)
                ROOT_AUDIOBOOKS to detail
            }
            else -> null
        }
    }

    // ---- File access --------------------------------------------------------

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        val (root, detail) = resolveItem(itemId)
            ?: throw ChitankaException("Unknown item: $itemId")
        if (root == ROOT_BOOKS) {
            if (format != BookFormat.Epub) {
                throw ChitankaException("Only EPUB is supported for Chitanka books; got $format")
            }
            val url = detail.downloadUrl
                ?: throw ChitankaException("No EPUB link on $itemId")
            return CatalogFileHandle.Stream(url = url, format = BookFormat.Epub)
        }
        // Audiobook — use AudiobookMediaCapability.buildStreamUrl per-track instead.
        throw ChitankaException(
            "Audiobook file handles are per-track — use AudiobookMediaCapability.buildStreamUrl"
        )
    }

    override suspend fun openFile(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
    ): CatalogFileStream = withContext(Dispatchers.IO) {
        val handle = fetchFile(itemId, format)
        if (handle !is CatalogFileHandle.Stream) {
            throw ChitankaException("Local file handles are not produced by Chitanka Source")
        }
        val request = Request.Builder().url(handle.url).build()
        val response = bytesClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw ChitankaException("Failed to fetch bytes for $itemId: ${response.code}")
        }
        val body = response.body
        object : CatalogFileStream {
            override val contentLength: Long = body.contentLength()
            override fun byteStream() = body.byteStream()
            override fun close() { response.close() }
        }
    }

    // ---- Connectivity -------------------------------------------------------

    override suspend fun connectivityCheck(): CatalogHealth = coroutineScope {
        val start = System.currentTimeMillis()
        val chitankaOk = async(Dispatchers.IO) { http.ping(ChitankaScraper.BASE) }
        val gramofoncheOk = async(Dispatchers.IO) { http.ping(GramofoncheScraper.BASE) }
        val ok = chitankaOk.await() && gramofoncheOk.await()
        CatalogHealth(
            isReachable = ok,
            serverVersion = null,
            latencyMs = System.currentTimeMillis() - start,
            error = if (ok) null else "One of chitanka.info / gramofonche.chitanka.info is unreachable",
        )
    }

    // ---- SeriesCapability ---------------------------------------------------

    /**
     * Enumerates the full series catalogue for the Books library by walking
     * `/series/alpha/{letter}` for every Cyrillic letter. Cheap-ish (~30 requests) and only
     * runs at Source install; subsequent reads come from the Room cache the repository layer
     * warms with these results (Slice 7, follow-up PR).
     */
    override suspend fun listSeries(rootId: String): List<CatalogSeries> {
        if (rootId != ROOT_BOOKS) return emptyList()
        val pairs = coroutineScope {
            CYRILLIC_LETTERS.map { letter ->
                async(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(letter, "UTF-8")
                    val html = runCatching {
                        http.getString("${ChitankaScraper.BASE}/series/alpha/$encoded")
                    }.getOrNull() ?: return@async emptyList()
                    ChitankaScraper.parseSeriesAlphaPage(html)
                }
            }.awaitAll().flatten()
        }
        return pairs
            .distinctBy { it.first }
            .map { (slug, label) ->
                CatalogSeries(
                    id = slug,
                    rootId = ROOT_BOOKS,
                    name = label,
                    coverUrl = null,
                    bookCount = 0,
                )
            }
    }

    override suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem> {
        if (rootId != ROOT_BOOKS) return emptyList()
        val url = "${ChitankaScraper.BASE}/$seriesId"
        val html = http.getString(url)
        return ChitankaScraper.parseSearchResults(html).items
            .map { it.toCatalogItem(ROOT_BOOKS) }
    }

    // ---- AudiobookMediaCapability ------------------------------------------

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> {
        val (root, detail) = resolveItem(itemId) ?: return emptyList()
        if (root != ROOT_AUDIOBOOKS) return emptyList()
        // Gramofonche exposes no per-track duration metadata, so estimate from Content-Length
        // via HEAD + the site's uniform ~128 kbps MP3 encoding. This isn't perfectly accurate
        // but gives non-zero startOffsetSec / durationSec so the audiobook player's global
        // timeline math (AudiobookTracks#trackAt / #absoluteSec) resolves the correct track
        // across a multi-file book — with zeros, the resolver collapses every playback position
        // onto the last track's timeline and multi-track resume/scrubbing/chapter-nav break.
        val durationsSec = coroutineScope {
            detail.downloads.map { d ->
                async(Dispatchers.IO) {
                    val bytes = http.headContentLength(d.url) ?: return@async 0.0
                    bytes.toDouble() / GRAMOFONCHE_ASSUMED_BYTES_PER_SEC
                }
            }.awaitAll()
        }
        var cumulativeStart = 0.0
        return detail.downloads.mapIndexed { idx, d ->
            val dur = durationsSec[idx]
            val track = CatalogAudioTrack(
                ino = d.url,
                index = idx,
                startOffsetSec = cumulativeStart,
                durationSec = dur,
                contentUrl = d.url,
                mimeType = "audio/mpeg",
            )
            cumulativeStart += dur
            track
        }
    }

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? {
        // No dedup fingerprint on this Source — return null (contract: no audiobook / cannot fingerprint).
        return null
    }

    override fun buildStreamUrl(itemId: String, trackIno: String): String {
        // Gramofonche URLs are self-authenticating (public, no token). The ino IS the URL.
        return trackIno
    }

    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? {
        val tracks = getTracks(itemId)
        if (tracks.isEmpty()) return null
        return buildAudiobookStream(tracks)
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        // Chapter markers are absent on Gramofonche by design — chapter nav degrades to track nav.
        return emptyList()
    }

    // ---- Helpers ------------------------------------------------------------

    private fun ChitankaBookSummary.toCatalogItem(rootId: String): CatalogItem {
        // Derive the Source-local id: URL path without the leading slash. This is stable
        // (see ADR 0042). `/book/…` and `/text/…` share the same numeric part but different
        // slugs — `getItem` promotes to `/text/` before persisting, but the summary keeps
        // whatever the listing produced.
        val id = url.removePrefix(ChitankaScraper.BASE + "/")
            .removePrefix(GramofoncheScraper.BASE + "/")
            .trimEnd('/')
        val format = if (this.format == "epub") BookFormat.Epub else BookFormat.Audiobook
        val hasAudio = this.format == "mp3"
        return CatalogItem(
            id = id,
            rootId = rootId,
            title = title,
            author = authors.joinToString(", "),
            coverUrl = coverUrl,
            ebookFormat = format,
            hasAudio = hasAudio,
            audioDurationSec = 0.0,
            description = null,
            language = "Bulgarian",
        )
    }

    companion object {
        const val ROOT_BOOKS = "books"
        const val ROOT_AUDIOBOOKS = "audiobooks"

        internal val CYRILLIC_LETTERS = listOf(
            "А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й",
            "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У",
            "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ю", "Я",
        )

        internal val EDITORIAL_COLLECTIONS: List<CatalogFacet> = listOf(
            CatalogFacet(key = "collection:school", label = "Училищна програма", sortOrder = 10_000),
            CatalogFacet(key = "collection:university", label = "Университет", sortOrder = 10_001),
        )

        /**
         * Gramofonche's MP3 encoding is broadly ~128 kbps. Duration is estimated as
         * `Content-Length / 16 000` when the origin does not report duration directly. Off by ~5%
         * for the occasional 96 kbps or 160 kbps title — small enough that the timeline resolver
         * still picks the correct track, and the player corrects the absolute position from
         * ExoPlayer's real duration once decoded.
         */
        internal const val GRAMOFONCHE_ASSUMED_BYTES_PER_SEC: Long = 16_000L

        internal val AUDIO_FACETS: List<CatalogFacet> = listOf(
            CatalogFacet(key = "prikazki", label = "Приказки", sortOrder = 1),
            CatalogFacet(key = "pesnicki", label = "Песнички", sortOrder = 2),
            CatalogFacet(key = "zagolemi", label = "За по-големи", sortOrder = 3),
        )

        /**
         * Assembles a Gramofonche audiobook stream from its per-track spans. The total duration
         * is the sum of track durations — leaving it at 0 makes the player's timeline math
         * collapse (AbsolutePositionPlayer falls back to ExoPlayer's per-track duration, so the
         * UI shows 0 until the current track resolves and never reflects the whole book).
         */
        internal fun buildAudiobookStream(tracks: List<CatalogAudioTrack>): CatalogAudiobookStream =
            CatalogAudiobookStream(
                trackUrls = tracks.map { it.contentUrl },
                tracks = tracks,
                chapters = emptyList(),
                totalDurationSec = tracks.sumOf { it.durationSec },
                serverCurrentTimeSec = 0.0,
                serverLastUpdate = 0L,
            )
    }
}

internal class ChitankaException(message: String) : RuntimeException(message)
