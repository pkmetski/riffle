package com.riffle.core.catalog.gutenberg

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileRetryPolicy
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.FacetedSearchCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.withCatalogFileStream
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import java.net.URLEncoder

/**
 * The Project Gutenberg-backed [Catalog]. Serves a single Library — Books — from the
 * public Gutendex API (https://gutendex.com), which mirrors the Project Gutenberg catalogue
 * as JSON. Downloads route through gutenberg.org's canonical EPUB URLs.
 *
 * Anonymous read-only public API — no credentials, no persistent server-side state. All I/O
 * routes through [GutenbergHttpClient] which retries transient 429/503 and identifies the
 * caller with a stable User-Agent. Parsing is delegated to [GutenbergParser] (pure functions,
 * module-internal types); this class translates those into `core:catalog` vocabulary.
 *
 * Capability shape:
 * - Mandatory core: implemented.
 * - [OfflineBrowseCapability]: marker mixin — page-cache TTL logic lives in a wrapper repository
 *   layer (not this class).
 * - [DownloadsCapability], [ToReadListCapability], [ReadCapability],
 *   [FacetedSearchCapability]: yes.
 * - Absent: Series (Gutendex has bookshelves, not per-title series metadata), Collections,
 *   Playlists, Progress, ReadingSessions, Stats, Bookmarks, AudiobookMedia.
 */
class GutenbergCatalog(
    private val http: GutenbergHttpClient,
    private val bytesClient: HttpClient = HttpClient(),
    private val userAgent: String = "Riffle",
    // Overridable so tests can point the API + download hosts at a MockWebServer.
    private val apiBase: String = GutenbergParser.BASE,
) : Catalog,
    OfflineBrowseCapability,
    DownloadsCapability,
    ToReadListCapability,
    ReadCapability,
    FacetedSearchCapability {

    override val sourceType: SourceType = SourceType.GUTENBERG

    // ---- Roots --------------------------------------------------------------

    override suspend fun listRoots(): List<CatalogRoot> = listOf(
        CatalogRoot(id = ROOT_BOOKS, name = "Books", mediaType = "book"),
    )

    // ---- Facets -------------------------------------------------------------

    /**
     * Chip strip contents: curated Gutendex topics and language filters. Kept small so the strip
     * is scannable at a glance on a phone; power users can search instead.
     */
    override suspend fun listFacets(rootId: String): List<CatalogFacet> = when (rootId) {
        ROOT_BOOKS -> DEFAULT_FACETS
        else -> emptyList()
    }

    // ---- Browse -------------------------------------------------------------

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        if (rootId != ROOT_BOOKS) return emptyList()
        val url = browseUrlFor(facet, page)
        val body = http.getString(url)
        val listing = GutenbergParser.parseListing(body)
        // Gutendex returns 32 books per page — smaller than Riffle's typical 50. Slice into the
        // requested page-size window so an eager caller asking for pageSize=100 gets one Gutendex
        // page (32 items) rather than mishandled expectations. Grow if needed via `next` walking
        // in a future patch; today's Library screen calls page=0,pageSize=50 and pages one page
        // per scroll threshold, so page:1-to-1 mapping is fine.
        return listing.items.map { it.toCatalogItem() }.take(pageSize)
    }

    internal fun browseUrlFor(facet: FacetSelection?, page: Int): String {
        return booksUrl(page = page, facet = facet)
    }

    // ---- Search -------------------------------------------------------------

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> = search(
        rootId = rootId,
        query = query,
        page = page,
        pageSize = pageSize,
        facet = null,
    )

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        if (rootId != ROOT_BOOKS) return emptyList()
        if (query.isBlank()) return emptyList()
        val url = booksUrl(
            page = page,
            facet = facet,
            extraParams = listOf("search=${URLEncoder.encode(query.trim(), "UTF-8")}"),
        )
        val body = http.getString(url)
        return GutenbergParser.parseListing(body).items
            .map { it.toCatalogItem() }
            .take(pageSize)
    }

    // ---- Item lookup --------------------------------------------------------

    override suspend fun getItem(itemId: String): CatalogItem? {
        val numericId = itemId.toLongOrNull() ?: return null
        val url = "$apiBase/books/$numericId"
        val body = runCatching { http.getString(url) }.getOrNull() ?: return null
        val summary = GutenbergParser.parseBook(body) ?: return null
        return summary.toCatalogItem()
    }

    // ---- File access --------------------------------------------------------

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        if (format != BookFormat.Epub) {
            throw GutenbergException("Only EPUB is supported for Gutenberg; got $format")
        }
        val summary = resolveSummary(itemId)
            ?: throw GutenbergException("Unknown Gutenberg item: $itemId")
        val url = summary.epubUrl
            ?: throw GutenbergException("No EPUB link on Gutenberg $itemId")
        return CatalogFileHandle.Stream(
            url = url,
            headers = mapOf(HttpHeaders.UserAgent to userAgent),
            format = BookFormat.Epub,
        )
    }

    override suspend fun <T> withFileStream(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        val handle = fetchFile(itemId, format)
        if (handle !is CatalogFileHandle.Stream) {
            throw GutenbergException("Local file handles are not produced by Gutenberg Source")
        }
        return streamDownloadHandle(handle, itemId, block)
    }

    internal suspend fun <T> withBytesWithRetry(
        downloadUrl: String,
        itemId: String,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        val handle = CatalogFileHandle.Stream(
            url = downloadUrl,
            headers = mapOf(HttpHeaders.UserAgent to userAgent),
            format = BookFormat.Epub,
        )
        return streamDownloadHandle(handle, itemId, block)
    }

    private suspend fun <T> streamDownloadHandle(
        handle: CatalogFileHandle.Stream,
        itemId: String,
        block: suspend (CatalogFileStream) -> T,
    ): T = bytesClient.withCatalogFileStream(
        handle = handle,
        retryPolicy = CatalogFileRetryPolicy(
            statusCodes = setOf(429, 503),
            delaysMs = GutenbergHttpClient.DEFAULT_RETRY_DELAYS_MS,
        ),
        httpFailure = { failure ->
            GutenbergException("Failed to fetch bytes for $itemId: ${failure.code}")
        },
        block = block,
    )

    // ---- Connectivity -------------------------------------------------------

    override suspend fun connectivityCheck(): CatalogHealth {
        val start = System.currentTimeMillis()
        val ok = http.ping(apiBase)
        return CatalogHealth(
            isReachable = ok,
            serverVersion = null,
            latencyMs = System.currentTimeMillis() - start,
            error = if (ok) null else "gutendex.com is unreachable",
        )
    }

    // ---- Helpers ------------------------------------------------------------

    /** Fetch the raw summary (kept internal so [fetchFile] can read the EPUB URL). */
    private suspend fun resolveSummary(itemId: String): GutenbergBookSummary? {
        val numericId = itemId.toLongOrNull() ?: return null
        val url = "$apiBase/books/$numericId"
        val body = runCatching { http.getString(url) }.getOrNull() ?: return null
        return GutenbergParser.parseBook(body)
    }

    private fun booksUrl(
        page: Int,
        facet: FacetSelection?,
        extraParams: List<String> = emptyList(),
    ): String {
        val pageNum = page + 1 // Gutendex pagination is 1-indexed.
        val qs = buildList {
            add("page=$pageNum")
            addAll(facetQueryParams(facet))
            addAll(extraParams)
        }.joinToString("&")
        return "$apiBase/books/?$qs"
    }

    private fun facetQueryParams(facet: FacetSelection?): List<String> {
        val key = facet?.key ?: return emptyList()
        return when {
            key.startsWith(TOPIC_PREFIX) -> {
                listOf("topic=${URLEncoder.encode(key.removePrefix(TOPIC_PREFIX), "UTF-8")}")
            }
            key.startsWith(LANGUAGE_PREFIX) -> {
                listOf("languages=${URLEncoder.encode(key.removePrefix(LANGUAGE_PREFIX), "UTF-8")}")
            }
            else -> emptyList()
        }
    }

    private fun GutenbergBookSummary.toCatalogItem(): CatalogItem = CatalogItem(
        id = id.toString(),
        rootId = ROOT_BOOKS,
        title = title,
        author = authors.joinToString(", "),
        coverUrl = coverUrl,
        ebookFormat = BookFormat.Epub,
        hasAudio = false,
        audioDurationSec = 0.0,
        description = description,
        genres = subjects.take(6), // Gutendex subjects are Library-of-Congress fine-grained; cap.
        language = languages.firstOrNull()?.let(::languageDisplayName),
    )

    /** Map ISO-639 language codes returned by Gutendex to a human-readable label. */
    private fun languageDisplayName(code: String): String = when (code.lowercase()) {
        "en" -> "English"
        "fr" -> "French"
        "de" -> "German"
        "es" -> "Spanish"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "nl" -> "Dutch"
        "la" -> "Latin"
        "el" -> "Greek"
        "ru" -> "Russian"
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "fi" -> "Finnish"
        "sv" -> "Swedish"
        "pl" -> "Polish"
        else -> code
    }

    companion object {
        const val ROOT_BOOKS: String = "books"
        private const val TOPIC_PREFIX: String = "topic:"
        private const val LANGUAGE_PREFIX: String = "language:"

        /**
         * Curated topic facets. Gutendex accepts `topic=<label>` as a case-insensitive substring
         * match on both `subjects` and `bookshelves`, so the labels below are stable filters
         * against Project Gutenberg's Library-of-Congress subject taxonomy.
         */
        internal val DEFAULT_TOPICS: List<CatalogFacet> = listOf(
            CatalogFacet(key = "${TOPIC_PREFIX}fiction", label = "Fiction", sortOrder = 1),
            CatalogFacet(key = "${TOPIC_PREFIX}children", label = "Children's", sortOrder = 2),
            CatalogFacet(key = "${TOPIC_PREFIX}history", label = "History", sortOrder = 3),
            CatalogFacet(key = "${TOPIC_PREFIX}poetry", label = "Poetry", sortOrder = 4),
            CatalogFacet(key = "${TOPIC_PREFIX}philosophy", label = "Philosophy", sortOrder = 5),
            CatalogFacet(key = "${TOPIC_PREFIX}science", label = "Science", sortOrder = 6),
            CatalogFacet(key = "${TOPIC_PREFIX}biography", label = "Biography", sortOrder = 7),
            CatalogFacet(key = "${TOPIC_PREFIX}drama", label = "Drama", sortOrder = 8),
            CatalogFacet(key = "${TOPIC_PREFIX}adventure", label = "Adventure", sortOrder = 9),
            CatalogFacet(key = "${TOPIC_PREFIX}mystery", label = "Mystery", sortOrder = 10),
        )

        /**
         * Curated Gutendex language filters. The API accepts comma-separated ISO-639 two-letter
         * codes in a `languages` query parameter; Riffle exposes one language at a time through
         * the existing single-selection facet strip.
         */
        internal val DEFAULT_LANGUAGES: List<CatalogFacet> = listOf(
            CatalogFacet(key = "${LANGUAGE_PREFIX}en", label = "English", sortOrder = 101),
            CatalogFacet(key = "${LANGUAGE_PREFIX}fr", label = "French", sortOrder = 102),
            CatalogFacet(key = "${LANGUAGE_PREFIX}de", label = "German", sortOrder = 103),
            CatalogFacet(key = "${LANGUAGE_PREFIX}es", label = "Spanish", sortOrder = 104),
            CatalogFacet(key = "${LANGUAGE_PREFIX}it", label = "Italian", sortOrder = 105),
            CatalogFacet(key = "${LANGUAGE_PREFIX}pt", label = "Portuguese", sortOrder = 106),
            CatalogFacet(key = "${LANGUAGE_PREFIX}nl", label = "Dutch", sortOrder = 107),
            CatalogFacet(key = "${LANGUAGE_PREFIX}fi", label = "Finnish", sortOrder = 108),
            CatalogFacet(key = "${LANGUAGE_PREFIX}sv", label = "Swedish", sortOrder = 109),
            CatalogFacet(key = "${LANGUAGE_PREFIX}pl", label = "Polish", sortOrder = 110),
        )

        internal val DEFAULT_FACETS: List<CatalogFacet> = DEFAULT_TOPICS + DEFAULT_LANGUAGES
    }
}

internal class GutenbergException(message: String) : RuntimeException(message)
