package com.riffle.core.catalog.komga

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogSeries
import com.riffle.core.catalog.CatalogSeriesEntry
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
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
 * - [SeriesCapability]: series-of-books grouping (Komga's core concept).
 * - [PlaylistsCapability]: server-side "To Read" sync backed by Komga's `/api/v1/readlists`.
 *   Komga readlists are server-wide (not per-library), so the shared list is find-or-created once
 *   and both the browse and detail toggles operate on it; per-library views are produced by
 *   filtering the readlist's book ids to those in the requested library.
 * - [ProgressPeerCapability] (#528): Komga stores a per-book read-progress record with a `page`
 *   integer (1-indexed) plus a `completed` flag. Position dialect is [CfiDialect.PAGE_NUMBER]: the
 *   local Room store's opaque locator string is a page number for CBZ/PDF books opened from
 *   Komga, and the ebook remote passes it through verbatim (no CFI translation). For reflowable
 *   EPUB books served from Komga a locator-to-page mapping does not yet exist — sync degrades to
 *   the local-only state (`get()` returns a stale RemoteProgress; PATCH is best-effort).
 * - Absent: CollectionsCapability, ReadingSessionsCapability, StatsCapability,
 *   AudiobookMediaCapability, AudiobookProgressPeerCapability, BookmarksCapability,
 *   ReadaloudCapability.
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
    PlaylistsCapability,
    ProgressPeerCapability {

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

    // region ProgressPeerCapability (#528)

    /**
     * Komga stores the book position as a 1-indexed `page` integer, but Riffle's PDF and CBZ
     * readers persist a Readium Locator JSON as the local position (e.g.
     * `{"href":"publication.pdf","locations":{"position":18,"fragments":["page=18"]}}`). This
     * catalog acts as the boundary translator: on push, extract the page from the locator; on
     * pull, embed the server page back into a Locator JSON the reader can navigate to. The
     * dialect stays [CfiDialect.PAGE_NUMBER] so the shared engine bypasses the CFI translator (it
     * would produce nonsense for Komga positions); Komga itself owns the JSON↔page conversion.
     */
    override val cfiDialect: CfiDialect = CfiDialect.PAGE_NUMBER

    override suspend fun pushEbookProgress(
        itemId: String,
        location: String,
        progress: Float,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long? {
        val page = extractPageFromLocation(location)
        // Honour the tri-state contract of [ProgressPeerCapability.pushEbookProgress]: `null`
        // must leave the server's `completed` flag untouched. Prior code derived
        // `completed = isFinished ?: (progress >= 1f)`, which routinely sent `completed = false`
        // on every reader-position save — so re-reading a book that Komga had marked completed
        // silently un-finished it on the server the first time the sweep fired. The KomgaJson
        // config drops null fields (`explicitNulls = false`), so passing `isFinished` through
        // verbatim omits the field for routine saves and only mark-finished/mark-unread callers
        // touch it.
        if (page == null && isFinished == null) {
            // Komga rejects a PATCH with all-null fields — nothing to send.
            return null
        }
        val body = KomgaJson.encodeToString(
            KomgaReadProgressPatch.serializer(),
            KomgaReadProgressPatch(page = page, completed = isFinished),
        )
        http.patchJson(apiUrl("books/$itemId/read-progress"), body)
        // Komga's PATCH response is 204 No Content — no server stamp. Caller adopts the client
        // clock (see CatalogEbookProgressRemote / ReadingSessionRepositoryImpl).
        return null
    }

    override suspend fun pullProgress(itemId: String): CatalogProgress? {
        val body = runCatching { http.getString(apiUrl("books/$itemId")) }.getOrNull() ?: return null
        val dto = runCatching { KomgaJson.decodeFromString(serializer<KomgaBookDto>(), body) }.getOrNull()
            ?: return null
        return dto.toCatalogProgress()
    }

    override suspend fun pullAllProgress(): List<CatalogProgress> {
        // Sweep every book across every library that has a non-null readProgress. Komga has no
        // "list all my progress" endpoint, but /books returns readProgress embedded, so we page
        // through and filter. read_status=READ|IN_PROGRESS narrows the set server-side.
        //
        // Per-page try/catch: a mid-stream failure on a large library (a 5xx on page N, malformed
        // JSON, timeout) previously threw all the way to LibraryRepositoryImpl.refresh which
        // caught and returned emptyList — every never-opened row got seeded at readingProgress=0
        // and no later refresh could fix it (updateMetadata preserves local). Returning what we
        // have so far is strictly better: In-Progress shows the books we DID pull, and the next
        // sweep can catch the tail. Same "best-effort partial" shape as ProgressSweep uses in
        // ADR 0030.
        val out = mutableListOf<CatalogProgress>()
        var page = 0
        while (true) {
            val body = try {
                http.getString(
                    apiUrl("books") +
                        "?read_status=IN_PROGRESS,READ" +
                        "&size=$KOMGA_MAX_PAGE_SIZE&page=$page",
                )
            } catch (_: Throwable) {
                break
            }
            val pageDto = runCatching {
                KomgaJson.decodeFromString(
                    KomgaPageDto.serializer(serializer<KomgaBookDto>()),
                    body,
                )
            }.getOrNull() ?: break
            pageDto.content.forEach { book ->
                book.toCatalogProgress()?.let { out += it }
            }
            if (pageDto.last || pageDto.content.isEmpty()) break
            page++
        }
        return out
    }

    private fun KomgaBookDto.toCatalogProgress(): CatalogProgress? {
        val rp = readProgress ?: return null
        val totalPages = media.pagesCount?.takeIf { it > 0 }
        val progress = when {
            rp.completed -> 1f
            totalPages != null -> (rp.page.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
        val lastUpdate = parseIsoInstant(rp.lastModified) ?: parseIsoInstant(rp.readDate) ?: 0L
        // Emit a Readium Locator JSON so the reader can navigate to the server-side page directly
        // (Riffle's PDF/CBZ readers consume Locator JSON, not bare page numbers). Href defaults to
        // `publication.pdf` for the PDF profile and `publication.epub` for CBZ/EPUB, matching what
        // Readium's Streamer synthesises for single-file publications — the readers ignore the
        // href for single-resource books and consume `locations.position` / `fragments[0]=page=N`.
        val locator = if (rp.page > 0) locatorJsonForPage(rp.page, totalPages, media.mediaProfile) else null
        return CatalogProgress(
            itemId = id,
            ebookLocation = locator,
            ebookProgress = progress,
            audioCurrentTime = 0.0,
            audioDuration = 0.0,
            isFinished = rp.completed,
            finishedAt = if (rp.completed) lastUpdate.takeIf { it > 0L } else null,
            lastUpdate = lastUpdate,
        )
    }

    // endregion

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
            val nextBookIds = if (initialItemId != null && initialItemId !in existing.bookIds) {
                val merged = existing.bookIds + initialItemId
                patchReadListBookIds(existing.id, merged)
                merged
            } else {
                existing.bookIds
            }
            // Return the true post-PATCH state so the PlaylistsCapability contract ("returned
            // playlist already contains that item") holds on the reuse branch too. Callers that
            // trust the return value stay correct.
            return CatalogPlaylist(
                id = existing.id,
                rootId = rootId,
                name = existing.name,
                bookCount = nextBookIds.size,
                itemIds = nextBookIds,
            )
        }
        // Komga's POST /api/v1/readlists requires a non-empty bookIds; that's why the interface
        // exposes [initialItemId]. If the caller passes null AND no readlist exists yet, Komga
        // will reject the request — callers targeting Komga must always provide a seed on first
        // create (ToReadRepositoryImpl already does this via the addToToRead path).
        val seed = listOfNotNull(initialItemId)
        val body = KomgaJson.encodeToString(
            KomgaReadListCreateDto.serializer(),
            KomgaReadListCreateDto(name = name, bookIds = seed),
        )
        val response = http.postJson(apiUrl("readlists"), body)
        val created = KomgaJson.decodeFromString(KomgaReadListDto.serializer(), response)
        return CatalogPlaylist(
            id = created.id,
            rootId = rootId,
            name = created.name,
            bookCount = seed.size,
            itemIds = seed,
        )
    }

    override suspend fun addItemToPlaylist(playlistId: String, itemId: String) {
        // Komga's PATCH replaces bookIds wholesale — read-modify-write to preserve order and
        // append idempotently.
        val current = fetchReadList(playlistId)
        refuseIfFiltered(current, action = "add $itemId")
        if (itemId in current.bookIds) return
        patchReadListBookIds(playlistId, current.bookIds + itemId)
    }

    override suspend fun removeItemFromPlaylist(playlistId: String, itemId: String) {
        val current = fetchReadList(playlistId)
        refuseIfFiltered(current, action = "remove $itemId")
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
     * A Komga readlist with `filtered=true` is one where Komga hid one or more books from THIS
     * caller because they lack access (e.g. sharedLibrariesIds restrictions, admin-revoked
     * library access mid-session). The returned `bookIds` list is INCOMPLETE, so a PATCH built
     * from it would silently DELETE the hidden books from the server-side readlist — permanent
     * data loss the user cannot see. Refuse the mutation and let the caller surface the failure
     * to the user (via [ToReadRepositoryImpl]'s revert path); the readlist stays intact until
     * an admin restores access.
     */
    private fun refuseIfFiltered(readList: KomgaReadListDto, action: String) {
        if (readList.filtered) {
            throw KomgaHttpException(
                code = 409,
                url = apiUrl("readlists/${readList.id}"),
                method = "GUARD",
                statusMessage = "readlist $action refused",
                responseBody = "Komga returned filtered=true; the local view is incomplete. " +
                    "PATCHing would silently delete books the caller can't see — refusing.",
            )
        }
    }

    /**
     * The current authenticated user's id (from `GET /api/v1/users/me`), cached for the lifetime
     * of this catalog instance on success. On failure the call THROWS — an earlier version
     * silently returned null on any error, which collapsed the ownership predicate to
     * `ownerId == null || ownerId == null` for every readlist. On modern Komga (>= 1.19) all
     * readlists carry a non-null `ownerId`, so a transient `/users/me` 5xx would filter out
     * every readlist the user actually owns and cause `createPlaylist` to POST a duplicate on
     * every subsequent operation until the cache repopulated. Failing loud lets the repo layer's
     * `runCatching` log via `RIFFLE_TOREAD` and revert the optimistic add instead of silently
     * corrupting server state.
     */
    @Volatile private var cachedCurrentUserId: String? = null

    private suspend fun currentUserId(): String {
        cachedCurrentUserId?.let { return it }
        val body = http.getString(apiUrl("users/me")) // throws KomgaHttpException on non-2xx
        val me = KomgaJson.decodeFromString(KomgaCurrentUserDto.serializer(), body)
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

        /**
         * Parse the 1-indexed page number out of the reader's persisted position (#528).
         * Handles: (a) a bare integer string `"42"` (fixtures / round-tripped Komga pull);
         * (b) a Readium Locator JSON with `locations.position` (Riffle's PDF/CBZ readers save
         * this — `{"href":...,"locations":{"position":18,"fragments":["page=18"]}}`); (c) a
         * `page=N` fragment on the locator, as a fallback if `position` is absent. Returns null
         * for anything else (e.g. an epub.js CFI or an unparseable string), which makes the push
         * a no-op — the correct graceful-degrade for a Komga-backed EPUB whose reader locator
         * hasn't been mapped to a page yet.
         */
        internal fun extractPageFromLocation(location: String): Int? {
            val trimmed = location.trim()
            if (trimmed.isEmpty()) return null
            trimmed.toIntOrNull()?.let { return it.takeIf { p -> p > 0 } }
            if (!trimmed.startsWith("{")) return null
            val root = runCatching { KomgaJson.parseToJsonElement(trimmed) as? kotlinx.serialization.json.JsonObject }
                .getOrNull() ?: return null
            val locations = root["locations"] as? kotlinx.serialization.json.JsonObject ?: return null
            (locations["position"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                ?.let { return it.takeIf { p -> p > 0 } }
            val fragments = locations["fragments"] as? kotlinx.serialization.json.JsonArray ?: return null
            for (el in fragments) {
                val s = (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val eq = s.indexOf('=')
                if (eq > 0 && s.substring(0, eq).equals("page", ignoreCase = true)) {
                    s.substring(eq + 1).toIntOrNull()?.let { return it.takeIf { p -> p > 0 } }
                }
            }
            return null
        }

        /**
         * Build a minimal Readium Locator JSON positioning at [page] of [totalPages] in a
         * single-resource Komga book (#528). The reader consumes `locations.position` and
         * `fragments=["page=N"]` — matching exactly the shape the PDF reader persists locally, so
         * the inbound (`ServerWins`) branch of the sync cycle can hand the locator straight to
         * the Navigator without any further translation.
         */
        internal fun locatorJsonForPage(page: Int, totalPages: Int?, mediaProfile: String?): String {
            val progression = if (totalPages != null && totalPages > 0) {
                (page.toDouble() / totalPages.toDouble()).coerceIn(0.0, 1.0)
            } else 0.0
            val isEpub = mediaProfile?.equals("EPUB", ignoreCase = true) == true
            val (href, type) = if (isEpub) {
                "publication.epub" to "application/epub+zip"
            } else {
                "publication.pdf" to "application/pdf"
            }
            return buildString {
                append('{')
                append("\"href\":\"").append(href).append("\",")
                append("\"type\":\"").append(type).append("\",")
                append("\"locations\":{")
                append("\"position\":").append(page).append(',')
                append("\"fragments\":[\"page=").append(page).append("\"],")
                append("\"progression\":").append(progression).append(',')
                append("\"totalProgression\":").append(progression)
                append('}')
                append('}')
            }
        }

        internal fun parseActuatorVersion(body: String): String? {
            val obj = runCatching { KomgaJson.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject } }
                .getOrNull() ?: return null
            val build = (obj["build"] as? kotlinx.serialization.json.JsonObject) ?: return null
            val version = (build["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return version?.takeIf { it.isNotBlank() }
        }
    }
}
