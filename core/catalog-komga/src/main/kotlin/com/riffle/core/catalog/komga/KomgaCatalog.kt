package com.riffle.core.catalog.komga

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogSeries
import com.riffle.core.catalog.CatalogSeriesEntry
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
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
 * - [PlaylistsCapability]: server-side "To Read" sync backed by Komga's `/api/v1/readlists`.
 *   Komga readlists are server-wide (not per-library), so the shared list is find-or-created once
 *   and both the browse and detail toggles operate on it; per-library views are produced by
 *   filtering the readlist's book ids to those in the requested library.
 * - Absent: CollectionsCapability, ProgressPeerCapability, ReadingSessionsCapability,
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
    SeriesCapability,
    PlaylistsCapability {

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

    // region PlaylistsCapability

    /**
     * Summary of every readlist on the Komga server, with an empty `itemIds` list. Enumerating
     * per-library book ids requires one extra request per readlist (`/readlists/{id}/books?
     * library_id=…`), so [listPlaylists] deliberately skips that work and callers that need the
     * bookIds (e.g. the To Read sync) should go through [findPlaylist] instead.
     */
    override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> =
        fetchAllReadLists().map { rl ->
            CatalogPlaylist(
                id = rl.id,
                rootId = rootId,
                name = rl.name,
                bookCount = 0,
                itemIds = emptyList(),
            )
        }

    override suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist? {
        // Match by name AND ownership. A readlist that we CAN'T modify (owned by another user)
        // is worse than nothing — reusing it means every add/remove 403s. Treat it as absent so
        // [createPlaylist] falls through to POSTing a fresh readlist owned by the current user.
        // Komga allows duplicate readlist names, so we can coexist with someone else's "To Read".
        val readList = firstOwnedReadListNamed(name) ?: return null
        val bookIdsInLibrary = fetchReadListBookIdsInLibrary(readList.id, rootId)
        return CatalogPlaylist(
            id = readList.id,
            rootId = rootId,
            name = readList.name,
            bookCount = bookIdsInLibrary.size,
            itemIds = bookIdsInLibrary,
        )
    }

    override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist {
        // Komga readlists are server-wide, not per-library. Re-check by name AND ownership
        // before POST — concurrent first-time refreshes across two libraries shouldn't duplicate
        // OUR "To Read", but we also must NOT hand back a readlist owned by another user (that
        // would 403 on the next PATCH). See [firstOwnedReadListNamed].
        val existing = firstOwnedReadListNamed(name)
        if (existing != null) {
            // Existing readlist — append initialItemId (idempotent) so the caller's expectation
            // ("this playlist now contains the item I asked to seed") holds regardless of whether
            // the list existed before.
            if (initialItemId != null && initialItemId !in existing.bookIds) {
                patchReadListBookIds(existing.id, existing.bookIds + initialItemId)
            }
            return CatalogPlaylist(
                id = existing.id,
                rootId = rootId,
                name = existing.name,
                bookCount = 0,
                itemIds = emptyList(),
            )
        }
        // Komga's POST /api/v1/readlists requires a non-empty bookIds; that's why the interface
        // exposes [initialItemId]. If the caller passes null AND no readlist exists yet, Komga
        // will reject the request — callers targeting Komga must always provide a seed on first
        // create (ToReadRepositoryImpl already does this via the addToToRead path).
        val body = KomgaJson.encodeToString(
            KomgaReadListCreateDto.serializer(),
            KomgaReadListCreateDto(
                name = name,
                bookIds = listOfNotNull(initialItemId),
            ),
        )
        val response = http.postJson(apiUrl("readlists"), body)
        val created = KomgaJson.decodeFromString(KomgaReadListDto.serializer(), response)
        return CatalogPlaylist(
            id = created.id,
            rootId = rootId,
            name = created.name,
            bookCount = if (initialItemId != null) 1 else 0,
            itemIds = if (initialItemId != null) listOf(initialItemId) else emptyList(),
        )
    }

    override suspend fun addItemToPlaylist(playlistId: String, itemId: String) {
        // Komga's PATCH replaces bookIds wholesale — read-modify-write to preserve order and
        // append idempotently.
        val current = fetchReadList(playlistId)
        if (itemId in current.bookIds) return
        patchReadListBookIds(playlistId, current.bookIds + itemId)
    }

    override suspend fun removeItemFromPlaylist(playlistId: String, itemId: String) {
        val current = fetchReadList(playlistId)
        val next = current.bookIds.filterNot { it == itemId }
        if (next.isEmpty()) {
            // Komga does NOT auto-delete empty readlists (ABS does). Mirror the ABS empty-list
            // sweep here so the "To Read" name is free to be re-created on the next add; the
            // caller-side snapshot already drops playlistId when itemIds go to empty (see
            // ToReadRepositoryImpl.removeFromToRead).
            http.delete(apiUrl("readlists/$playlistId"))
        } else {
            patchReadListBookIds(playlistId, next)
        }
    }

    /**
     * The current authenticated user's id (from `GET /api/v1/users/me`), cached for the lifetime
     * of this catalog instance. Null when Komga refuses the query (e.g. older server without
     * this endpoint, or transient auth failure) — callers then fall back to treating ownership
     * as unknown, which is safe: any read/write that fails still gets logged via RIFFLE_TOREAD.
     */
    @Volatile private var cachedCurrentUserId: String? = null

    private suspend fun currentUserId(): String? {
        cachedCurrentUserId?.let { return it }
        val body = runCatching { http.getString(apiUrl("users/me")) }.getOrNull() ?: return null
        val me = runCatching { KomgaJson.decodeFromString(KomgaCurrentUserDto.serializer(), body) }.getOrNull()
            ?: return null
        cachedCurrentUserId = me.id
        return me.id
    }

    /**
     * First readlist named [name] whose owner is the currently authenticated user. Readlists
     * without an [KomgaReadListDto.ownerId] (older Komga < 1.19) are considered a match — we
     * have no ownership signal there and the writes will either succeed or fail loudly. A
     * readlist owned by SOMEONE ELSE is skipped so we don't hand back an id that will 403 on
     * every subsequent PATCH — this is the exact regression the Komga integration hit against
     * a "To Read" readlist that had been created via Komga's web UI by a different user.
     */
    private suspend fun firstOwnedReadListNamed(name: String): KomgaReadListDto? {
        val meId = currentUserId()
        return fetchAllReadLists().firstOrNull { rl ->
            rl.name == name && (rl.ownerId == null || rl.ownerId == meId)
        }
    }

    private suspend fun fetchAllReadLists(): List<KomgaReadListDto> {
        val out = mutableListOf<KomgaReadListDto>()
        var page = 0
        while (true) {
            val body = http.getString(apiUrl("readlists") + "?size=$KOMGA_MAX_PAGE_SIZE&page=$page")
            val pageDto = KomgaJson.decodeFromString(
                KomgaPageDto.serializer(serializer<KomgaReadListDto>()),
                body,
            )
            out += pageDto.content
            if (pageDto.last || pageDto.content.isEmpty()) break
            page++
        }
        return out
    }

    private suspend fun fetchReadList(id: String): KomgaReadListDto {
        val body = http.getString(apiUrl("readlists/$id"))
        return KomgaJson.decodeFromString(KomgaReadListDto.serializer(), body)
    }

    private suspend fun fetchReadListBookIdsInLibrary(readListId: String, libraryId: String): List<String> {
        val out = mutableListOf<String>()
        var page = 0
        while (true) {
            val url = apiUrl("readlists/$readListId/books") +
                "?library_id=${URLEncoder.encode(libraryId, "UTF-8")}" +
                "&size=$KOMGA_MAX_PAGE_SIZE&page=$page"
            val body = http.getString(url)
            val pageDto = KomgaJson.decodeFromString(
                KomgaPageDto.serializer(serializer<KomgaBookDto>()),
                body,
            )
            pageDto.content.forEach { out += it.id }
            if (pageDto.last || pageDto.content.isEmpty()) break
            page++
        }
        return out
    }

    private suspend fun patchReadListBookIds(id: String, bookIds: List<String>) {
        val body = KomgaJson.encodeToString(
            KomgaReadListUpdateDto.serializer(),
            KomgaReadListUpdateDto(bookIds = bookIds),
        )
        http.patchJson(apiUrl("readlists/$id"), body)
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
        // Komga's `created` is when the book row landed on the Komga server — the canonical
        // "added" timestamp for Recently Added. Falling through as null caused
        // LibraryRepositoryImpl.refreshLibraryItems to stamp `addedAt = clock.nowMs()` on every
        // refresh, and updateMetadata overwrites the column, so every book got a fresh ~now on
        // every library open. All timestamps tie, ORDER BY addedAt DESC is tie-broken by row
        // insertion order, and the top-5 Recently Added visibly reshuffled every refresh.
        addedAt = parseIsoInstant(created),
        isbn = metadata.isbn,
        asin = null,
        readingProgress = null,
        updatedAt = parseIsoInstant(lastModified),
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
            // Komga's /api/v1/books has no author-sort key — authors are a multi-role list on
            // book metadata, not a top-level column. Falling back to titleSort keeps the result
            // order deterministic (better than the server's default insertion order); a truly
            // author-sorted view would require a client-side re-sort of the fully-paged set,
            // which is out of scope for this catalog until a bulk-item cursor exists.
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

        /**
         * Parse Komga's ISO-8601 timestamp strings (e.g. `2026-06-06T19:20:23Z`) into epoch
         * milliseconds. Returns null when [raw] is null, blank, or unparseable — the caller then
         * leaves `addedAt` unset and `LibraryRepositoryImpl.refreshLibraryItems` falls back to
         * `clock.nowMs()`, which is meaningless but at least not a crash.
         */
        internal fun parseIsoInstant(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
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
