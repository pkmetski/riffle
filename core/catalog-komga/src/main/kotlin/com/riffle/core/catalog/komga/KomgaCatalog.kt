package com.riffle.core.catalog.komga

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
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
import com.riffle.core.domain.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Komga-backed [Catalog]. One instance per configured Komga Source row. Reads over the
 * documented `/api/v1` REST surface using HTTP Basic auth baked into [KomgaCatalogConfig].
 *
 * Capability shape:
 * - Mandatory core (listRoots/browse/search/getItem/fetchFile/openFile/connectivityCheck): yes.
 * - [ReadCapability], [DownloadsCapability], [ToReadListCapability], [OfflineBrowseCapability]:
 *   marker mixins so the shared library UI enables the corresponding affordances.
 * - Absent: SeriesCapability (planned — Komga has strong series concepts, but they belong to a
 *   later slice), CollectionsCapability, ProgressPeerCapability, ReadingSessionsCapability,
 *   StatsCapability, AudiobookMediaCapability, BookmarksCapability, ReadaloudCapability.
 */
class KomgaCatalog(
    private val config: KomgaCatalogConfig,
    private val http: KomgaHttpClient,
    private val bytesClient: OkHttpClient = OkHttpClient(),
) : Catalog,
    ReadCapability,
    DownloadsCapability,
    ToReadListCapability,
    OfflineBrowseCapability,
    SeriesCapability {

    override val sourceType: SourceType = SourceType.KOMGA

    override suspend fun listRoots(): List<CatalogRoot> {
        val body = http.getString(apiUrl("libraries"))
        val libs = KomgaJson.decodeFromString(ListSerializer(serializer<KomgaLibraryDto>()), body)
        return libs.map { CatalogRoot(id = it.id, name = it.name, mediaType = "book") }
    }

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        // `pageSize == Int.MAX_VALUE` is the sentinel repository-side callers use to mean "give
        // me the whole library at once" (`LibraryRepositoryImpl.refreshLibraryItems` fires this
        // for the Room-mirror refresh). Komga caps a single page at 1000 items — for the 2500-book
        // Comics library previously ~1500 rows silently vanished from `library_items`, which then
        // made `SeriesDao.observeItemsBySeriesId`'s INNER JOIN return empty ("No books in this
        // series" on the series-detail screen). Sweep every page here so the refresh sees the
        // whole library; paged callers (grid scroll) keep the single-page path.
        return if (pageSize == Int.MAX_VALUE) sweepAllBooks(rootId, sort) else {
            val url = booksBrowseUrl(rootId, sort, page, pageSize)
            fetchBookPage(url, rootId)
        }
    }

    private suspend fun sweepAllBooks(rootId: String, sort: SortKey): List<CatalogItem> {
        val out = mutableListOf<CatalogItem>()
        var page = 0
        while (true) {
            val body = http.getString(booksBrowseUrl(rootId, sort, page, KOMGA_MAX_PAGE_SIZE))
            val pageDto = KomgaJson.decodeFromString(
                KomgaPageDto.serializer(serializer<KomgaBookDto>()),
                body,
            )
            pageDto.content.forEach { out += it.toCatalogItem() }
            if (pageDto.last || pageDto.content.isEmpty()) break
            page++
        }
        return out
    }

    private fun booksBrowseUrl(rootId: String, sort: SortKey, page: Int, size: Int): String =
        apiUrl("books") +
            "?library_id=${URLEncoder.encode(rootId, "UTF-8")}" +
            "&page=$page&size=$size" +
            "&sort=${sortParamFor(sort)}"

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        if (query.isBlank()) return emptyList()
        val url = apiUrl("books") +
            "?library_id=${URLEncoder.encode(rootId, "UTF-8")}" +
            "&search=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&page=$page&size=$pageSize"
        return fetchBookPage(url, rootId)
    }

    override suspend fun getItem(itemId: String): CatalogItem? {
        val body = runCatching { http.getString(apiUrl("books/$itemId")) }.getOrNull() ?: return null
        val dto = runCatching { KomgaJson.decodeFromString(serializer<KomgaBookDto>(), body) }.getOrNull()
            ?: return null
        return dto.toCatalogItem()
    }

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        if (format == BookFormat.Audiobook || format == BookFormat.Unsupported) {
            throw KomgaHttpException(code = 415, url = itemId, message = "Unsupported format $format")
        }
        return CatalogFileHandle.Stream(
            url = apiUrl("books/$itemId/file"),
            headers = mapOf("Authorization" to config.basicAuthHeader),
            format = format,
        )
    }

    override suspend fun openFile(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
    ): CatalogFileStream = withContext(Dispatchers.IO) {
        // OkHttp's `Call.execute()` is BLOCKING and throws `NetworkOnMainThreadException` when it
        // runs on Main — which is where readers previously routed via `catalog.openFile(...)`. This
        // was the actual root cause behind the "Network error: null" reader error surfaced to the
        // user (the Throwable's own message is null; the class name only shows via `toString()`).
        // Hop to IO here so every network path in this catalog stays off the Main thread.
        if (format == BookFormat.Audiobook || format == BookFormat.Unsupported) {
            throw KomgaHttpException(code = 415, url = itemId, message = "Unsupported format $format")
        }
        val url = apiUrl("books/$itemId/file")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", config.basicAuthHeader)
            .header("Accept", "application/octet-stream, application/epub+zip, application/pdf, application/x-cbz")
            .build()
        val response = try {
            bytesClient.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw KomgaHttpException(
                code = -1,
                url = url,
                message = e.message ?: e::class.simpleName ?: "network error",
            )
        }
        if (!response.isSuccessful) {
            val code = response.code
            val msg = response.message.ifEmpty { "HTTP $code" }
            response.close()
            throw KomgaHttpException(code = code, url = url, message = msg)
        }
        val body = response.body
        object : CatalogFileStream {
            override val contentLength: Long = body.contentLength()
            override fun byteStream(): java.io.InputStream = body.byteStream()
            override fun close() { response.close() }
        }
    }

    override suspend fun connectivityCheck(): CatalogHealth {
        val start = System.currentTimeMillis()
        val actuator = runCatching { http.getString(rawUrl("actuator/info")) }.getOrNull()
        val elapsed = System.currentTimeMillis() - start
        val version = actuator?.let { parseActuatorVersion(it) }
        // Fall back to a HEAD on /api/v1/libraries when actuator is disabled/forbidden — Komga
        // gates the actuator behind ADMIN by default, so a non-admin user sees 403 there but 200
        // on /libraries.
        val reachable = version != null ||
            http.getStatus(apiUrl("libraries")).let { it in 200..399 }
        return CatalogHealth(
            isReachable = reachable,
            serverVersion = version,
            latencyMs = elapsed,
            error = if (reachable) null else "Komga is unreachable",
        )
    }

    // region SeriesCapability

    override suspend fun listSeries(rootId: String): List<CatalogSeries> {
        // Optimisation: instead of fetching /series then N × /series/{id}/books (one HTTP round
        // trip per series), sweep the library's books once — each Komga book DTO carries seriesId
        // and metadata.numberSort — and group them locally. For a 2 500-book / ~100-series comics
        // library that's ~3 paged calls instead of ~100. Series metadata (name, cover) still comes
        // from the /series endpoint; we join the two by id.
        val bookIdsBySeries = HashMap<String, MutableList<CatalogSeriesEntry>>()
        var bookPage = 0
        while (true) {
            val body = http.getString(
                apiUrl("books") +
                    "?library_id=${URLEncoder.encode(rootId, "UTF-8")}" +
                    "&size=1000&page=$bookPage",
            )
            val pageDto = KomgaJson.decodeFromString(
                KomgaPageDto.serializer(serializer<KomgaBookDto>()),
                body,
            )
            pageDto.content.forEach { book ->
                val sid = book.seriesId ?: return@forEach
                bookIdsBySeries.getOrPut(sid) { mutableListOf() }.add(
                    CatalogSeriesEntry(
                        itemId = book.id,
                        sequence = book.metadata.number?.takeIf { it.isNotBlank() }
                            ?: book.number?.let { formatNumber(it) },
                    ),
                )
            }
            if (pageDto.last || pageDto.content.isEmpty()) break
            bookPage++
        }

        val out = mutableListOf<CatalogSeries>()
        var page = 0
        while (true) {
            val body = http.getString(
                apiUrl("series") +
                    "?library_id=${URLEncoder.encode(rootId, "UTF-8")}" +
                    "&size=1000&page=$page",
            )
            val pageDto = KomgaJson.decodeFromString(
                KomgaPageDto.serializer(serializer<KomgaSeriesDto>()),
                body,
            )
            pageDto.content.forEach { s ->
                val entries = bookIdsBySeries[s.id].orEmpty()
                out += CatalogSeries(
                    id = s.id,
                    rootId = s.libraryId,
                    name = s.metadata.title?.takeIf { it.isNotBlank() } ?: s.name,
                    coverUrl = apiUrl("series/${s.id}/thumbnail"),
                    bookCount = s.booksCount.takeIf { it > 0 } ?: entries.size,
                    items = entries,
                )
            }
            if (pageDto.last || pageDto.content.isEmpty()) break
            page++
        }
        return out
    }

    override suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem> {
        val body = http.getString(
            apiUrl("series/$seriesId/books") + "?size=1000&page=0",
        )
        val pageDto = KomgaJson.decodeFromString(
            KomgaPageDto.serializer(serializer<KomgaBookDto>()),
            body,
        )
        return pageDto.content.map { it.toCatalogItem() }
    }

    // endregion

    private suspend fun fetchBookPage(url: String, rootId: String): List<CatalogItem> {
        val body = http.getString(url)
        val page = KomgaJson.decodeFromString(
            KomgaPageDto.serializer(serializer<KomgaBookDto>()),
            body,
        )
        return page.content.map { it.toCatalogItem() }
    }

    private fun KomgaBookDto.toCatalogItem(): CatalogItem = CatalogItem(
        id = id,
        rootId = libraryId,
        title = metadata.title?.takeIf { it.isNotBlank() } ?: name,
        author = metadata.authors
            .filter { it.role.equals("writer", ignoreCase = true) }
            .ifEmpty { metadata.authors }
            .joinToString(", ") { it.name }
            .ifBlank { "" },
        coverUrl = apiUrl("books/$id/thumbnail"),
        ebookFormat = bookFormatFor(media),
        hasAudio = false,
        audioDurationSec = 0.0,
        description = metadata.summary?.takeIf { it.isNotBlank() },
        seriesName = seriesTitle,
        seriesSequence = number?.let { formatNumber(it) },
        publishedYear = metadata.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
        genres = metadata.tags.take(6),
        publisher = null,
        language = null,
        addedAt = null,
        isbn = metadata.isbn,
        asin = null,
        readingProgress = null,
        updatedAt = null,
    )

    internal fun apiUrl(path: String): String = "${config.baseUrl.trimEnd('/')}/api/v1/$path"
    internal fun rawUrl(path: String): String = "${config.baseUrl.trimEnd('/')}/$path"

    companion object {
        /**
         * Komga always sets a `mediaProfile` on ingested books:
         *  - `DIVINA` for comic archives (regardless of the container's mediaType, which is often
         *    the generic `application/zip` — this is why keying on mediaType alone treats every
         *    CBZ as Unsupported and greys out the whole Comics library).
         *  - `EPUB` for EPUBs.
         *  - `PDF` for PDFs.
         * Fall back to mediaType only when the profile is missing (older Komga installs).
         */
        internal fun bookFormatFor(media: KomgaBookMediaDto): BookFormat {
            when (media.mediaProfile?.uppercase()) {
                "DIVINA" -> return BookFormat.Cbz
                "EPUB" -> return BookFormat.Epub
                "PDF" -> return BookFormat.Pdf
            }
            return media.mediaType.toBookFormat()
        }

        internal fun String?.toBookFormat(): BookFormat = when {
            this == null -> BookFormat.Unsupported
            equals("application/epub+zip", ignoreCase = true) -> BookFormat.Epub
            equals("application/pdf", ignoreCase = true) -> BookFormat.Pdf
            equals("application/x-cbz", ignoreCase = true) ||
                equals("application/vnd.comicbook+zip", ignoreCase = true) -> BookFormat.Cbz
            else -> BookFormat.Unsupported
        }

        internal fun sortParamFor(sort: SortKey): String = when (sort) {
            SortKey.TITLE -> "metadata.titleSort,asc"
            SortKey.AUTHOR -> "metadata.titleSort,asc"
            SortKey.ADDED_AT -> "createdDate,desc"
            SortKey.PUBLISHED_YEAR -> "metadata.releaseDate,asc"
            SortKey.RECENTLY_OPENED -> "readProgress.readDate,desc"
        }

        internal fun formatNumber(n: Float): String? {
            if (n == 0f) return null
            val asInt = n.toInt()
            return if (asInt.toFloat() == n) asInt.toString() else n.toString()
        }

        /** Komga's server-side page-size cap. Requests larger than this are silently truncated. */
        internal const val KOMGA_MAX_PAGE_SIZE = 1000

        internal fun parseActuatorVersion(body: String): String? {
            val obj = runCatching { KomgaJson.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject } }
                .getOrNull() ?: return null
            val build = (obj["build"] as? kotlinx.serialization.json.JsonObject) ?: return null
            val version = (build["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return version?.takeIf { it.isNotBlank() }
        }
    }
}
