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
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.models.SourceType
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
    // Must match the UA [ChitankaHttpClient] sends for HTML fetches — chitanka.info
    // throttles/blocks requests carrying the raw `okhttp/x.x.x` default. Without this the
    // EPUB download URL 429s on the first request even though browse succeeds.
    private val userAgent: String = "Riffle",
) : Catalog,
    SeriesCapability,
    AudiobookMediaCapability,
    OfflineBrowseCapability,
    DownloadsCapability,
    ToReadListCapability,
    ReadCapability {

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
        // Both chitanka.info and gramofonche.chitanka.info return the FULL listing on a single
        // HTML page — the `?page=N` query param is silently ignored (verified against
        // gramofonche.chitanka.info/prikazki/ = 442 items, byte-identical response for ?page=2).
        // Slice with drop+take so callers requesting page N>0 actually receive the next window.
        // Without this, ChitankaBrowseViewModel's lazy-loading refetches the same first 50 items
        // over and over and its id-dedup filters them all out — visually "pagination is broken".
        return listing.items.map { it.toCatalogItem(rootId) }.drop(page * pageSize).take(pageSize)
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
        val resolvedAudioDurationSec = resolveAudioDurationSec(
            root = root,
            scraped = base.audioDurationSec,
            hasDownloads = detail.downloads.isNotEmpty(),
        ) { tracksFor(detail).sumOf { it.durationSec } }
        return base.copy(
            audioDurationSec = resolvedAudioDurationSec,
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
        // Same UA/headers as [ChitankaHttpClient.getString] — required or chitanka.info 429s
        // the download URL. Also mirror its 429 backoff schedule (1.5s, 3s) for parity.
        val response = fetchBytesWith429Retry(handle.url, itemId)
        val body = response.body
        object : CatalogFileStream {
            override val contentLength: Long = body.contentLength()
            override fun byteStream() = body.byteStream()
            override fun close() { response.close() }
        }
    }

    internal suspend fun fetchBytesWith429Retry(url: String, itemId: String): okhttp3.Response {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "bg,en;q=0.5")
                .header("Referer", ChitankaScraper.BASE)
                .build()
            val response = bytesClient.newCall(request).execute()
            if (response.isSuccessful) return response
            val code = response.code
            response.close()
            if (code == 429 && attempt < ChitankaHttpClient.DEFAULT_RETRY_DELAYS_MS.size) {
                kotlinx.coroutines.delay(ChitankaHttpClient.DEFAULT_RETRY_DELAYS_MS[attempt])
                attempt++
                continue
            }
            throw ChitankaException("Failed to fetch bytes for $itemId: $code")
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
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
        return tracksFor(detail)
    }

    /**
     * Turn a resolved [ChitankaDetail] into per-track spans. Split out so the caller can share a
     * single detail-page fetch between tracks and chapter titles instead of hitting the network
     * twice per audiobook open — [openAudiobook] and [getAudiobookChapters] both need the
     * download list plus derived durations.
     *
     * Gramofonche exposes no per-track duration metadata, so each track is probed in parallel
     * via [ChitankaHttpClient.probeMp3DurationSec], which does a two-stage Range GET (10-byte
     * ID3v2 header → audio start) and reads the Xing/Info frame count for exact duration.
     * Gramofonche's rips are VBR-encoded with ~200 KiB ID3v2 cover-art tags — a naive
     * "sniff the first N bytes + divide by an assumed bitrate" collapses on both fronts, and
     * historically underestimated by up to ~40 %, leaving the scrubber pinned at end while
     * ExoPlayer was still decoding several real minutes of audio.
     *
     * If a probe fails (network error, unrecognised encoding) or the summed probes fall
     * significantly short of the scraped total, delegate to [resolveTrackDurationsSec] which
     * distributes the scraped total proportionally by content-length. The 128 kbps CBR
     * fallback in [GRAMOFONCHE_FALLBACK_BITRATE_BPS] only kicks in when *neither* signal is
     * available (probe null AND no scraped duration on the page).
     */
    private suspend fun tracksFor(detail: ChitankaDetail): List<CatalogAudioTrack> {
        val scrapedTotalSec = parseGramofoncheDurationSeconds(detail.duration)
        // Fast path: probe every track first. On the healthy VBR case (Xing tag present, sum
        // matches the scraped total) tier 1 in resolveTrackDurationsSec trusts the probes
        // verbatim and we can skip the per-track HEAD entirely — cheaper by N round-trips on
        // every audiobook open. Only fall back to fetching content-length when we know the
        // probes underestimate and the byte proportions are the recovery signal.
        val probed = coroutineScope {
            detail.downloads.map { d ->
                async(Dispatchers.IO) { http.probeMp3DurationSec(d.url)?.takeIf { it > 0.0 } }
            }.awaitAll()
        }
        val probeOnly = probed.map { TrackProbe(probedSec = it, bytes = 0L) }
        val probes = if (canTrustProbesAlone(probeOnly, scrapedTotalSec)) {
            probeOnly
        } else {
            val bytes = coroutineScope {
                detail.downloads.map { d ->
                    async(Dispatchers.IO) { http.headContentLength(d.url) ?: 0L }
                }.awaitAll()
            }
            probed.mapIndexed { i, p -> TrackProbe(probedSec = p, bytes = bytes[i]) }
        }
        val durationsSec = resolveTrackDurationsSec(probes, scrapedTotalSec)
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
        val (root, detail) = resolveItem(itemId) ?: return null
        if (root != ROOT_AUDIOBOOKS) return null
        val tracks = tracksFor(detail)
        if (tracks.isEmpty()) return null
        return buildAudiobookStream(tracks, detail.downloads.map { it.title })
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        // Gramofonche has no explicit chapter markers, but each MP3 in a multi-track book is a
        // discrete story/segment with its own title in the anchor text — mirror the ABS-upload
        // behaviour of the reference `chitanka-to-audiobookshelf` (each file becomes one chapter)
        // so the player's chapter drawer shows the track list instead of a blank sheet. On a
        // transient network failure fall back to an empty list so the drawer degrades gracefully
        // rather than propagating the exception up the ViewModel.
        return runCatching {
            val (root, detail) = resolveItem(itemId) ?: return@runCatching emptyList()
            if (root != ROOT_AUDIOBOOKS) return@runCatching emptyList()
            synthesizeChaptersFromTracks(tracksFor(detail), detail.downloads.map { it.title })
        }.getOrElse { emptyList() }
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
            audioDurationSec = if (hasAudio) parseGramofoncheDurationSeconds(duration) else 0.0,
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
         * Fallback CBR bitrate used when [ChitankaHttpClient.probeMp3DurationSec] returns null
         * (transient network error, missing Content-Range, unrecognised encoding). 128 kbps is
         * the historical upper bound of Gramofonche MP3s; using it as the fallback preserves
         * the pre-probe behaviour on the rare probe miss.
         */
        internal const val GRAMOFONCHE_FALLBACK_BITRATE_BPS: Int = 128_000

        /**
         * Ratio below which per-track probe results are considered untrustworthy relative to
         * the scraped total. The scraped duration on Gramofonche is rounded down to whole
         * minutes ("43мин" for 43.6 real minutes), so a genuine probe can legitimately exceed
         * the scraped value by up to one minute. Underestimating by >10 % is the signature of
         * probes falling through to the CBR fallback on VBR files (Gramofonche rips are ~72
         * kbps but [GRAMOFONCHE_FALLBACK_BITRATE_BPS] assumes 128 kbps, which halves the
         * duration and drove the "chapters total 25 min for a 43 min book" bug).
         */
        internal const val PROBE_UNDERESTIMATE_THRESHOLD: Double = 0.9

        internal data class TrackProbe(val probedSec: Double?, val bytes: Long)

        /**
         * Chooses per-track durations from what we have: probes (Xing-accurate when the
         * upstream MP3 exposes the tag), byte lengths (proportional distribution against the
         * scraped total), or the [GRAMOFONCHE_FALLBACK_BITRATE_BPS] CBR estimate. Preference
         * order:
         *
         * 1. All probes succeeded and their sum is at least
         *    [PROBE_UNDERESTIMATE_THRESHOLD] of the scraped total (or there is no scraped
         *    total to compare against) → trust the probes verbatim.
         * 2. Scraped total is known and content-length is known for every track → distribute
         *    the scraped total proportionally by bytes. This is the recovery path for VBR
         *    rips where the probe misses the Xing tag and CBR math would return roughly half
         *    the real duration.
         * 3. Otherwise fall back per-track to `probed ?: bytes*8 / 128 kbps`, matching the
         *    pre-recovery behaviour so a probe-less, duration-less page still yields
         *    non-zero spans for [AudiobookTracks#trackAt] routing.
         */
        internal fun resolveTrackDurationsSec(
            probes: List<TrackProbe>,
            scrapedTotalSec: Double,
        ): List<Double> {
            if (probes.isEmpty()) return emptyList()
            if (canTrustProbesAlone(probes, scrapedTotalSec)) {
                return probes.map { it.probedSec!! }
            }
            val totalBytes = probes.sumOf { it.bytes }
            val allBytesKnown = probes.all { it.bytes > 0L }
            if (scrapedTotalSec > 0.0 && allBytesKnown && totalBytes > 0L) {
                return probes.map { scrapedTotalSec * it.bytes / totalBytes }
            }
            // Last-resort per-track: probed value; else CBR math on bytes; else the equal
            // share of the scraped total. The equal-share step matters when at least one
            // track is missing both signals (probe null, HEAD failed → bytes=0) — without
            // it that track would emit 0.0 sec, cumulativeStart wouldn't advance, and
            // AudiobookTracks#trackAt would route every later position to the wrong track.
            val equalShare = if (scrapedTotalSec > 0.0) scrapedTotalSec / probes.size else 0.0
            return probes.map { p ->
                p.probedSec
                    ?: if (p.bytes > 0L) p.bytes.toDouble() * 8.0 / GRAMOFONCHE_FALLBACK_BITRATE_BPS
                    else equalShare
            }
        }

        private fun canTrustProbesAlone(probes: List<TrackProbe>, scrapedTotalSec: Double): Boolean {
            if (!probes.all { it.probedSec != null }) return false
            if (scrapedTotalSec <= 0.0) return true
            val probedSum = probes.sumOf { it.probedSec!! }
            return probedSum >= scrapedTotalSec * PROBE_UNDERESTIMATE_THRESHOLD
        }

        private val GRAMOFONCHE_HOURS_REGEX = Regex("(\\d+)\\s*часа?")
        private val GRAMOFONCHE_MINUTES_REGEX = Regex("(\\d+)\\s*мин")

        /**
         * Parses the scraped Gramofonche duration string into seconds so it can populate
         * `CatalogItem.audioDurationSec`. Handles the three shapes the site emits — `"45мин"`,
         * `"1час 20мин"`, `"2часа"` — by summing whichever hour and minute captures the regex
         * finds. `LibraryItemDetailScreen` gates the total-time line on `audioDurationSec > 0`;
         * when both captures miss (page never mentions a duration at all), `getItem` falls back
         * to summing per-track Xing probes so the line still renders.
         */
        /**
         * Picks the effective `audioDurationSec` for a Gramofonche detail page. Prefers the value
         * scraped from the "..NNмин" line (fast, no network) and falls back to summing per-track
         * Xing probes only when the scrape yielded 0. Some Gramofonche detail pages (compilations
         * and older reel-tape rips in particular) simply do not print a duration; without the
         * fallback, `LibraryItemDetailScreen`'s `audioDurationSec > 0` gate hides the total-time
         * row entirely. Probing costs one two-range GET per track (see [tracksFor]), so we only
         * pay it when we have downloads to probe AND nothing better on hand.
         */
        internal suspend fun resolveAudioDurationSec(
            root: String,
            scraped: Double,
            hasDownloads: Boolean,
            fetchProbedTotalSec: suspend () -> Double,
        ): Double {
            if (root != ROOT_AUDIOBOOKS || scraped > 0.0 || !hasDownloads) return scraped
            return runCatching { fetchProbedTotalSec() }.getOrDefault(0.0)
        }

        internal fun parseGramofoncheDurationSeconds(raw: String?): Double {
            if (raw.isNullOrEmpty()) return 0.0
            val hours = GRAMOFONCHE_HOURS_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = GRAMOFONCHE_MINUTES_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return (hours * 3600 + minutes * 60).toDouble()
        }

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
        internal fun buildAudiobookStream(
            tracks: List<CatalogAudioTrack>,
            trackTitles: List<String> = emptyList(),
        ): CatalogAudiobookStream =
            CatalogAudiobookStream(
                trackUrls = tracks.map { it.contentUrl },
                tracks = tracks,
                chapters = synthesizeChaptersFromTracks(tracks, trackTitles),
                totalDurationSec = tracks.sumOf { it.durationSec },
                serverCurrentTimeSec = 0.0,
                serverLastUpdate = 0L,
            )

        /**
         * Turn the per-track spans into chapter markers, one per MP3 file. Titles come from the
         * anchor text on the detail page (e.g. "Клан-недоклан", "Щъркел и лисица" for the 14-story
         * 14-малки БГ приказки disc); a track without a title falls back to "Track N" so the
         * chapter drawer never renders a blank row. Empty input → empty output.
         */
        internal fun synthesizeChaptersFromTracks(
            tracks: List<CatalogAudioTrack>,
            trackTitles: List<String>,
        ): List<CatalogAudiobookChapter> = tracks.mapIndexed { i, t ->
            val fallback = "Track ${i + 1}"
            val title = trackTitles.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: fallback
            CatalogAudiobookChapter(
                index = i,
                startSec = t.startOffsetSec,
                endSec = t.startOffsetSec + t.durationSec,
                title = title,
            )
        }
    }
}

internal class ChitankaException(message: String) : RuntimeException(message)
