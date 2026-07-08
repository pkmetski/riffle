package com.riffle.core.catalog.abs

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogCollection
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogSeries
import com.riffle.core.catalog.CatalogSeriesEntry
import com.riffle.core.catalog.CatalogSessionHandle
import com.riffle.core.catalog.CatalogStats
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.StatsCapability
import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.Clock
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.SourceType
import com.riffle.core.network.AbsAudioUrl
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsCoverUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAbsAudioTrack
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkSeries
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.errorAsThrowable

/**
 * The ABS-backed [Catalog] implementation. Wraps the existing ABS HTTP client (split across
 * [AbsLibraryApi], [AbsPlaybackApi], [AbsSessionApi], [AbsBookmarkApi], [AbsServerInfoApi]) and
 * implements every capability ABS provides (all eight).
 *
 * One [AbsCatalog] instance corresponds to one ABS Source row in the DB — the (baseUrl, token)
 * pair lives in [config], the (sourceId) namespace materialises at the repository boundary
 * (issue #434). Nothing in this class writes to local stores; repositories do that.
 *
 * Errors on any method surface as [CatalogException].
 */
class AbsCatalog(
    private val config: AbsCatalogConfig,
    private val libraryApi: AbsLibraryApi,
    private val playbackApi: AbsPlaybackApi,
    private val sessionApi: AbsSessionApi,
    private val bookmarkApi: AbsBookmarkApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val clock: Clock,
) : Catalog,
    SeriesCapability,
    CollectionsCapability,
    PlaylistsCapability,
    ProgressPeerCapability,
    ReadingSessionsCapability,
    StatsCapability,
    AudiobookMediaCapability,
    BookmarksCapability,
    OfflineBrowseCapability {

    override val sourceType: SourceType = SourceType.ABS

    // region Catalog — mandatory core

    override suspend fun listRoots(): List<CatalogRoot> =
        libraryApi.getLibraries(config.baseUrl, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogRoot() }

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        val items = libraryApi.getLibraryItems(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogItem() }
            .sortedWith(comparatorFor(sort))
        return items.pageOf(page, pageSize)
    }

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        // ABS's search endpoint takes `limit` (total cap) — not per-page — so request enough for
        // the page window, then slice client-side. Callers paging past `limit` get an empty list.
        val limit = ((page + 1) * pageSize).coerceAtLeast(pageSize)
        val hits = libraryApi.searchLibrary(config.baseUrl, rootId, query, limit, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogItem() }
        return hits.pageOf(page, pageSize)
    }

    override suspend fun getItem(itemId: String): CatalogItem? =
        libraryApi.getItem(config.baseUrl, itemId, config.token, config.insecureAllowed)
            .unwrap()
            ?.toCatalogItem()

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        val authHeaders = mapOf("Authorization" to "Bearer ${config.token}")
        return when (format) {
            BookFormat.Epub, BookFormat.Pdf -> {
                val ino = libraryApi.getItemEbookFileIno(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
                CatalogFileHandle.Stream(
                    url = "${config.baseUrl.trimEnd('/')}/api/items/$itemId/ebook/$ino",
                    headers = authHeaders,
                    format = format,
                )
            }
            BookFormat.Audiobook -> {
                // Audiobook streams are per-track — callers use AudiobookMediaCapability instead.
                throw CatalogException.UnsupportedFormat("Audiobook file handles are per-track — use AudiobookMediaCapability.buildStreamUrl")
            }
            BookFormat.Unsupported -> throw CatalogException.UnsupportedFormat("Cannot fetch Unsupported format")
        }
    }

    override suspend fun openFile(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
    ): CatalogFileStream {
        return when (format) {
            BookFormat.Epub, BookFormat.Pdf -> {
                val ino = handleHint?.takeIf { it.isNotEmpty() }
                    ?: libraryApi.getItemEbookFileIno(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
                val body = when (val r = libraryApi.downloadEpub(config.baseUrl, itemId, ino, config.token, config.insecureAllowed)) {
                    is NetworkResult.Success -> r.value
                    else -> throw CatalogException.Unknown(r.errorAsThrowable())
                }
                object : CatalogFileStream {
                    override val contentLength: Long = body.contentLength()
                    override fun byteStream(): java.io.InputStream = body.byteStream()
                    override fun close() { body.close() }
                }
            }
            BookFormat.Audiobook -> throw CatalogException.UnsupportedFormat(
                "Audiobook file streams are per-track — use AudiobookMediaCapability.buildStreamUrl",
            )
            BookFormat.Unsupported -> throw CatalogException.UnsupportedFormat("Cannot open Unsupported format")
        }
    }

    override suspend fun connectivityCheck(): CatalogHealth {
        // AbsApiClient.getServerInfo swallows failures and returns null on any error, so we can't
        // surface a specific error string — reachability collapses to (version != null).
        val startMs = clock.nowMs()
        val version = serverInfoApi.getServerInfo(config.baseUrl, config.token, config.insecureAllowed)
        return CatalogHealth(
            isReachable = version != null,
            serverVersion = version,
            latencyMs = clock.nowMs() - startMs,
        )
    }

    // endregion

    // region SeriesCapability

    override suspend fun listSeries(rootId: String): List<CatalogSeries> =
        libraryApi.getSeries(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogSeries() }

    override suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem> {
        // ABS returns series-with-embedded-books via the same endpoint used by listSeries;
        // scan the response for the matching series and map its books to CatalogItem.
        val series = libraryApi.getSeries(config.baseUrl, rootId, config.token, config.insecureAllowed).unwrap()
        val match = series.firstOrNull { it.id == seriesId } ?: return emptyList()
        return match.items.map { book ->
            CatalogItem(
                id = book.id,
                rootId = book.libraryId.ifEmpty { rootId },
                title = book.title,
                author = book.author,
                coverUrl = coverUrl(book.id, book.updatedAt),
                ebookFormat = book.ebookFormat.toCatalogFormat(hasAudio = book.hasAudio),
                hasAudio = book.hasAudio,
                audioDurationSec = book.audioDurationSec,
                ebookFileIno = book.ebookFileIno,
                description = book.description,
                seriesName = book.seriesName,
                seriesSequence = book.sequence,
                publishedYear = book.publishedYear,
                genres = book.genres,
                publisher = book.publisher,
                readingProgress = book.readingProgress,
                updatedAt = book.updatedAt,
            )
        }
    }

    // endregion

    // region CollectionsCapability

    override suspend fun listCollections(rootId: String): List<CatalogCollection> =
        libraryApi.getCollections(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogCollection() }

    override suspend fun createCollection(rootId: String, name: String): CatalogCollection {
        val created = libraryApi.createCollection(config.baseUrl, rootId, name, initialBookId = null, config.token, config.insecureAllowed)
            .unwrap()
            ?: throw CatalogException.Unknown(IllegalStateException("ABS returned null for createCollection"))
        return created.toCatalogCollection()
    }

    override suspend fun addItemToCollection(collectionId: String, itemId: String) {
        libraryApi.addBookToCollection(config.baseUrl, collectionId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun removeItemFromCollection(collectionId: String, itemId: String) {
        libraryApi.removeBookFromCollection(config.baseUrl, collectionId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    // endregion

    // region PlaylistsCapability

    override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> =
        libraryApi.getPlaylists(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogPlaylist() }

    override suspend fun createPlaylist(rootId: String, name: String): CatalogPlaylist {
        val created = libraryApi.createPlaylist(config.baseUrl, rootId, name, initialBookId = null, config.token, config.insecureAllowed)
            .unwrap()
            ?: throw CatalogException.Unknown(IllegalStateException("ABS returned null for createPlaylist"))
        return created.toCatalogPlaylist()
    }

    override suspend fun addItemToPlaylist(playlistId: String, itemId: String) {
        libraryApi.addBookToPlaylist(config.baseUrl, playlistId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun removeItemFromPlaylist(playlistId: String, itemId: String) {
        libraryApi.removeBookFromPlaylist(config.baseUrl, playlistId, itemId, config.token, config.insecureAllowed).unwrap()
    }

    // endregion

    // region ProgressPeerCapability

    override suspend fun pushEbookProgress(
        itemId: String,
        location: String,
        progress: Float,
        isFinished: Boolean,
        lastUpdateEpochMs: Long,
    ) {
        sessionApi.syncEbookProgress(
            config.baseUrl,
            itemId,
            NetworkEbookProgressPayload(ebookLocation = location, ebookProgress = progress, isFinished = isFinished),
            config.token,
            config.insecureAllowed,
        ).unwrap()
    }

    override suspend fun pushAudiobookProgress(
        itemId: String,
        currentTimeSec: Double,
        durationSec: Double,
        isFinished: Boolean,
        lastUpdateEpochMs: Long,
    ) {
        // ABS derives finished-state server-side from progress==1.0 for audiobook records (ADR 0029),
        // so the `isFinished` param is captured for capability parity but not forwarded here. A
        // Source that needs to explicitly signal "mark finished at t<duration" would have to send
        // the ebook side of the shared media-progress record (see `pushEbookProgress`) with the
        // same itemId — see project_mark_read_unread_audiobook_bug for the shape of that path.
        sessionApi.syncAudiobookProgress(
            config.baseUrl,
            itemId,
            NetworkAudiobookProgressPayload(currentTime = currentTimeSec, duration = durationSec),
            config.token,
            config.insecureAllowed,
        ).unwrap()
    }

    override suspend fun pullProgress(itemId: String): CatalogProgress? {
        val p = sessionApi.getProgress(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
        if (p.lastUpdate == 0L && p.ebookLocation.isEmpty() && p.currentTime == 0.0) return null
        return p.toCatalogProgress(itemId)
    }

    override suspend fun pullAllProgress(): List<CatalogProgress> =
        libraryApi.getUserProgress(config.baseUrl, config.token, config.insecureAllowed)
            .unwrap()
            .map { (id, p) ->
                CatalogProgress(
                    itemId = id,
                    ebookLocation = null,
                    ebookProgress = p.ebookProgress ?: 0f,
                    audioCurrentTime = 0.0,
                    audioDuration = 0.0,
                    isFinished = p.finishedAt != null,
                    finishedAt = p.finishedAt,
                    lastUpdate = p.lastUpdate ?: 0L,
                )
            }

    // endregion

    // region ReadingSessionsCapability

    override suspend fun openSession(itemId: String, deviceLabel: String): CatalogSessionHandle {
        val session = playbackApi.openPlaybackSession(config.baseUrl, itemId, config.deviceId, config.token, config.insecureAllowed).unwrap()
        val sessionId = session.sessionId
            ?: throw CatalogException.Unknown(IllegalStateException("ABS returned null sessionId for item $itemId"))
        return CatalogSessionHandle(
            sessionId = sessionId,
            itemId = itemId,
            startedAtEpochMs = clock.nowMs(),
        )
    }

    override suspend fun syncSession(handle: CatalogSessionHandle, currentTimeSec: Double, timeListenedSec: Double) {
        playbackApi.syncPlaybackSession(config.baseUrl, handle.sessionId, currentTimeSec, timeListenedSec, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun closeSession(handle: CatalogSessionHandle, currentTimeSec: Double, timeListenedSec: Double) {
        playbackApi.closePlaybackSession(config.baseUrl, handle.sessionId, currentTimeSec, timeListenedSec, config.token, config.insecureAllowed).unwrap()
    }

    // endregion

    // region StatsCapability

    override suspend fun getStats(): CatalogStats {
        val totalTime = serverInfoApi.getListeningStats(config.baseUrl, config.token, config.insecureAllowed).unwrap().totalTimeSec
        val progressRecords = libraryApi.getUserProgress(config.baseUrl, config.token, config.insecureAllowed).unwrap().values
        return CatalogStats(
            totalSecondsListened = totalTime,
            totalItemsInProgress = progressRecords.count { it.finishedAt == null },
            totalItemsFinished = progressRecords.count { it.finishedAt != null },
        )
    }

    // endregion

    // region AudiobookMediaCapability

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> {
        val tracks = libraryApi.getAudiobookTracks(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
        var running = 0.0
        return tracks.map { t ->
            val startOffset = running
            running += t.durationSec
            t.toCatalogAudioTrack(itemId, startOffset)
        }
    }

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint {
        val fp: AudiobookFingerprint = libraryApi.getAudiobookFingerprint(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
            ?: throw CatalogException.Unknown(IllegalStateException("Item $itemId has no audiobook"))
        return CatalogAudioFingerprint(
            itemId = itemId,
            fileSizeBytes = fp.fileSizeBytes,
            totalDurationSec = fp.durationSec,
            trackDurations = fp.trackDurationsSec,
        )
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        val detail = when (val r = libraryApi.getItemDetail(config.baseUrl, itemId, config.token, config.insecureAllowed)) {
            is NetworkResult.Success -> r.value
            else -> return emptyList()
        }
        return detail.media.chapters.mapIndexed { i, c ->
            CatalogAudiobookChapter(index = i, startSec = c.startSec, endSec = c.endSec, title = c.title)
        }
    }

    override fun buildStreamUrl(itemId: String, trackIno: String): String {
        val base = AbsAudioUrl.track(config.baseUrl, itemId, trackIno)
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}token=${config.token}"
    }

    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? {
        val session = when (
            val r = playbackApi.openPlaybackSession(config.baseUrl, itemId, config.deviceId, config.token, config.insecureAllowed)
        ) {
            is NetworkResult.Success -> r.value
            else -> return null
        }
        if (session.tracks.isEmpty()) return null

        val baseTrimmed = config.baseUrl.trimEnd('/')
        val trackUrls = session.tracks.map { t ->
            val path = if (t.contentUrl.startsWith("/")) t.contentUrl else "/${t.contentUrl}"
            val sep = if (t.contentUrl.contains("?")) "&" else "?"
            "$baseTrimmed$path${sep}token=${config.token}"
        }
        val tracks = session.tracks.map { t ->
            CatalogAudioTrack(
                ino = t.contentUrl.substringAfterLast("/"),
                index = t.index,
                startOffsetSec = t.startOffsetSec,
                durationSec = t.durationSec,
                contentUrl = "$baseTrimmed${if (t.contentUrl.startsWith("/")) t.contentUrl else "/${t.contentUrl}"}",
                mimeType = t.mimeType,
            )
        }
        val chapters = session.chapters.mapIndexed { i, c ->
            CatalogAudiobookChapter(index = i, startSec = c.startSec, endSec = c.endSec, title = c.title)
        }
        val serverLastUpdate = (
            sessionApi.getProgress(config.baseUrl, itemId, config.token, config.insecureAllowed) as? NetworkResult.Success
        )?.value?.lastUpdate ?: 0L

        return CatalogAudiobookStream(
            trackUrls = trackUrls,
            tracks = tracks,
            chapters = chapters,
            totalDurationSec = session.durationSec,
            serverCurrentTimeSec = session.currentTimeSec,
            serverLastUpdate = serverLastUpdate,
        )
    }

    // endregion

    // region BookmarksCapability

    override suspend fun listAllBookmarks(): List<CatalogBookmark> =
        bookmarkApi.listBookmarks(config.baseUrl, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogBookmark() }

    override suspend fun createBookmark(itemId: String, timeSec: Int, title: String): CatalogBookmark =
        bookmarkApi.createBookmark(config.baseUrl, itemId, timeSec, title, config.token, config.insecureAllowed)
            .unwrap()
            .toCatalogBookmark()

    override suspend fun deleteBookmark(itemId: String, timeSec: Int) {
        bookmarkApi.deleteBookmark(config.baseUrl, itemId, timeSec, config.token, config.insecureAllowed).unwrap()
    }

    override suspend fun renameBookmark(itemId: String, timeSec: Int, newTitle: String): CatalogBookmark =
        bookmarkApi.updateBookmark(config.baseUrl, itemId, timeSec, newTitle, config.token, config.insecureAllowed)
            .unwrap()
            .toCatalogBookmark()

    private fun NetworkAbsBookmark.toCatalogBookmark(): CatalogBookmark = CatalogBookmark(
        itemId = libraryItemId,
        timeSec = timeSec,
        title = title,
        createdAt = createdAt,
    )

    // endregion

    // region mappers

    private fun coverUrl(itemId: String, updatedAt: Long?): String =
        AbsCoverUrl.of(config.baseUrl, itemId, updatedAt)

    private fun NetworkLibrary.toCatalogRoot(): CatalogRoot = CatalogRoot(
        id = id,
        name = name,
        mediaType = mediaType,
        isUnsupported = mediaType == "podcast",
    )

    private fun NetworkLibraryItem.toCatalogItem(): CatalogItem = CatalogItem(
        id = id,
        rootId = libraryId,
        title = title,
        author = author,
        coverUrl = coverUrl(id, updatedAt),
        ebookFormat = ebookFormat.toCatalogFormat(hasAudio = hasAudio),
        hasAudio = hasAudio,
        audioDurationSec = audioDurationSec,
        ebookFileIno = ebookFileIno,
        description = description,
        seriesName = seriesName,
        publishedYear = publishedYear,
        genres = genres,
        publisher = publisher,
        language = language,
        addedAt = addedAt,
        isbn = isbn,
        asin = asin,
        readingProgress = readingProgress,
        updatedAt = updatedAt,
    )

    private fun NetworkSeries.toCatalogSeries(): CatalogSeries = CatalogSeries(
        id = id,
        rootId = libraryId,
        name = name,
        coverUrl = items.firstOrNull()?.let { coverUrl(it.id, it.updatedAt) },
        bookCount = bookCount,
        items = items.map { CatalogSeriesEntry(itemId = it.id, sequence = it.sequence) },
    )

    private fun NetworkCollection.toCatalogCollection(): CatalogCollection = CatalogCollection(
        id = id,
        rootId = libraryId,
        name = name,
        bookCount = bookCount,
        itemIds = items.map { it.id },
    )

    private fun NetworkPlaylist.toCatalogPlaylist(): CatalogPlaylist = CatalogPlaylist(
        id = id,
        rootId = libraryId,
        name = name,
        bookCount = bookCount,
        itemIds = items.map { it.id },
    )

    private fun NetworkServerProgress.toCatalogProgress(itemId: String): CatalogProgress = CatalogProgress(
        itemId = itemId,
        ebookLocation = ebookLocation.takeIf { it.isNotEmpty() },
        ebookProgress = ebookProgress,
        audioCurrentTime = currentTime,
        audioDuration = duration,
        // NetworkServerProgress lacks an explicit `finishedAt`, so derive the same way ABS does
        // server-side: ebook 100% OR audio at/past duration. Matches pullAllProgress, which reads
        // ABS's user-level `finishedAt` — either path answers the same question for the same item.
        isFinished = ebookProgress >= 1f || (duration > 0.0 && currentTime >= duration),
        lastUpdate = lastUpdate,
    )

    private fun NetworkAbsAudioTrack.toCatalogAudioTrack(itemId: String, startOffsetSec: Double): CatalogAudioTrack =
        CatalogAudioTrack(
            ino = ino,
            index = index,
            startOffsetSec = startOffsetSec,
            durationSec = durationSec,
            contentUrl = AbsAudioUrl.track(config.baseUrl, itemId, ino),
        )

    private fun EbookFormat.toCatalogFormat(hasAudio: Boolean = false): BookFormat = when (this) {
        EbookFormat.Epub -> BookFormat.Epub
        EbookFormat.Pdf -> BookFormat.Pdf
        EbookFormat.Unsupported -> if (hasAudio) BookFormat.Audiobook else BookFormat.Unsupported
    }

    private fun comparatorFor(sort: SortKey): Comparator<CatalogItem> = when (sort) {
        SortKey.TITLE -> compareBy { it.title.lowercase() }
        SortKey.AUTHOR -> compareBy { it.author.lowercase() }
        SortKey.ADDED_AT -> compareByDescending { it.addedAt ?: 0L }
        SortKey.PUBLISHED_YEAR -> compareBy { it.publishedYear ?: "" }
        // Last-opened is a per-device local concept ABS doesn't track. Repositories (#434) apply
        // this ordering on top of catalog output; the Catalog layer refuses so silent fall-through
        // to title-order can't mask the missing local-store lookup.
        SortKey.RECENTLY_OPENED -> throw CatalogException.UnsupportedFormat(
            "SortKey.RECENTLY_OPENED is a local ordering — apply it above the Catalog layer",
        )
    }

    private fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
        val from = (page * pageSize).coerceAtLeast(0)
        if (from >= size) return emptyList()
        val to = (from + pageSize).coerceAtMost(size)
        return subList(from, to)
    }

    // endregion
}
