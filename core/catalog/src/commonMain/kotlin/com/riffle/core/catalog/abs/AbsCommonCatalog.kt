package com.riffle.core.catalog.abs

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.common.Clock
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsAudioUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders

/**
 * The platform-neutral half of the ABS [Catalog]: everything that can be served from the
 * `commonMain` ABS HTTP interfaces ([AbsLibraryApi], [AbsSessionApi], [AbsServerInfoApi]).
 *
 * It exists because progress push is routed exclusively through `CatalogRegistry`
 * (`ReadingSessionRepositoryImpl`, `AudiobookRepositoryImpl`), and iOS registered no ABS
 * [com.riffle.core.catalog.CatalogFactory] at all — `AbsCatalog` is pinned to `jvmMain` by its
 * upload/import path (`java.io.File`) and its byte-streaming path (`core:network`'s
 * `AbsFileDownloadApi`). Reading a book on iPhone therefore never moved it on the user's ABS
 * server in either medium, while resume *from* the server kept working, so sync looked healthy
 * while being one-directional (#1071 §P0.1).
 *
 * `AbsCatalog` **delegates** its browse and progress-peer members to this class rather than
 * keeping a second copy (AGENTS.md: no private platform copies of shared derivations), so the
 * two platforms cannot drift. Everything `AbsCatalog` adds on top — import/upload, series,
 * collections, playlists, bookmarks, reading sessions, stats, audiobook media — stays there.
 *
 * The file-transfer pair ([fetchFile], [withFileStream]) are also fully implemented here using
 * [io.ktor.client.HttpClient] and [withHttpChannelStream] from `core:net`. ABS serves ebook bytes
 * from `/api/items/{id}/file/{ino}`; the inode is resolved lazily via [AbsLibraryApi.getItemEbookFileIno]
 * when the library-list API has not supplied it (which it never does in batch responses).
 */
class AbsCommonCatalog(
    private val config: AbsCatalogConfig,
    private val libraryApi: AbsLibraryApi,
    private val sessionApi: AbsSessionApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val clock: Clock,
    private val httpClient: HttpClient,
) : Catalog, ProgressPeerCapability, AudiobookProgressPeerCapability, AudiobookMediaCapability {

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
        facet: FacetSelection?,
    ): List<CatalogItem> {
        // ABS exposes no server-side facets today — `facet` is ignored.
        val items = libraryApi.getLibraryItems(config.baseUrl, rootId, config.token, config.insecureAllowed)
            .unwrap()
            .map { it.toCatalogItem(config.baseUrl) }
            .sortedWith(absComparatorFor(sort))
        return items.absPageOf(page, pageSize)
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
            .map { it.toCatalogItem(config.baseUrl) }
        return hits.absPageOf(page, pageSize)
    }

    override suspend fun getItem(itemId: String): CatalogItem? =
        libraryApi.getItem(config.baseUrl, itemId, config.token, config.insecureAllowed)
            .unwrap()
            ?.toCatalogItem(config.baseUrl)

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
        val ino = resolveIno(itemId, null)
        return CatalogFileHandle.Stream(
            url = absFileUrl(itemId, ino),
            headers = mapOf(HttpHeaders.Authorization to "Bearer ${config.token}"),
            format = format,
        )
    }

    override suspend fun <T> withFileStream(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        val ino = resolveIno(itemId, handleHint)
        val url = absFileUrl(itemId, ino)
        return httpClient.withHttpChannelStream(
            url = url,
            headers = mapOf(HttpHeaders.Authorization to "Bearer ${config.token}"),
        ) { stream ->
            block(object : CatalogFileStream {
                override val contentLength: Long = stream.contentLength
                override val channel = stream.channel
            })
        }
    }

    /**
     * Returns the file inode for [itemId]: uses [hint] if non-empty, otherwise fetches it from
     * the ABS `/api/items/{id}` endpoint. The library-list API does not include `ebookFile.ino`,
     * so a network call is required the first time a book is opened.
     */
    private suspend fun resolveIno(itemId: String, hint: String?): String =
        hint?.takeIf { it.isNotEmpty() }
            ?: libraryApi.getItemEbookFileIno(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()

    private fun absFileUrl(itemId: String, ino: String): String =
        "${config.baseUrl.trimEnd('/')}/api/items/$itemId/file/$ino"

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

    // region ProgressPeerCapability / AudiobookProgressPeerCapability

    override val cfiDialect: CfiDialect = CfiDialect.EPUB_JS

    override suspend fun pushEbookProgress(
        itemId: String,
        location: String,
        progress: Float,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long? = sessionApi.syncEbookProgress(
        config.baseUrl,
        itemId,
        // Leave `isFinished` nullable through to the payload: null = leave the audio dimension of
        // ABS's shared media-progress record untouched. Non-null zeroes the audio side per
        // NetworkEbookProgressPayload's contract — only mark-read/mark-unread callers do that.
        NetworkEbookProgressPayload(ebookLocation = location, ebookProgress = progress, isFinished = isFinished),
        config.token,
        config.insecureAllowed,
    ).unwrap()

    override suspend fun pushAudiobookProgress(
        itemId: String,
        currentTimeSec: Double,
        durationSec: Double,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long? {
        // ABS derives finished-state server-side from progress==1.0 for audiobook records (ADR 0035),
        // so the `isFinished` param is captured for capability parity but not forwarded here.
        return sessionApi.syncAudiobookProgress(
            config.baseUrl,
            itemId,
            NetworkAudiobookProgressPayload(currentTime = currentTimeSec, duration = durationSec),
            config.token,
            config.insecureAllowed,
        ).unwrap()
    }

    override suspend fun pullProgress(itemId: String): CatalogProgress? {
        // A successful GET always yields a CatalogProgress (fields may all be empty for a
        // never-touched item — callers detect that via `lastUpdate <= 0L`). A network failure
        // surfaces as a thrown [CatalogException] from `unwrap()`; the peer-adapter's runCatching
        // treats that as "unreachable" and returns null. Collapsing "reachable-empty" to null here
        // would make the two states indistinguishable and drop the first push on a fresh book.
        val p = sessionApi.getProgress(config.baseUrl, itemId, config.token, config.insecureAllowed).unwrap()
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
                    audioCurrentTime = p.currentTime,
                    audioDuration = p.duration,
                    // Derive isFinished from position data via the shared helper, identical to
                    // pullProgress / toCatalogProgress: ABS's isFinished/finishedAt are sticky flags
                    // that are NOT auto-cleared when another device advances the reading position, so
                    // trusting them would pin unifiedLibraryFraction() to 1f even when ebookProgress =
                    // 0.6. The helper consults the sticky flags only when there is no position data at
                    // all. Deriving differently here from toCatalogProgress is exactly what made the
                    // library card and detail screen disagree on Finished state.
                    isFinished = CatalogProgress.deriveIsFinished(
                        ebookProgress = p.ebookProgress ?: 0f,
                        audioCurrentTime = p.currentTime,
                        audioDuration = p.duration,
                        stickyFinished = p.isFinished,
                        stickyFinishedAt = p.finishedAt,
                    ),
                    finishedAt = p.finishedAt,
                    lastUpdate = p.lastUpdate ?: 0L,
                )
            }

    // endregion

    // region AudiobookMediaCapability
    //
    // `AbsCommonCatalog` implements `AudiobookMediaCapability` so that iOS surfaces the "Listen"
    // button for ABS audiobook items. iOS audio playback goes through `IosAbsAudiobookRepository`
    // (its own path) rather than `openAudiobook`, so `openAudiobook` correctly returns null here —
    // the capability signals that the source *has* audio media, not that audio plays via this path.

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> {
        val tracks = libraryApi.getAudiobookTracks(
            config.baseUrl, itemId, config.token, config.insecureAllowed,
        ).unwrap()
        var running = 0.0
        return tracks.map { t ->
            val startOffset = running
            running += t.durationSec
            CatalogAudioTrack(
                ino = t.ino,
                index = t.index,
                startOffsetSec = startOffset,
                durationSec = t.durationSec,
                contentUrl = AbsAudioUrl.track(config.baseUrl, itemId, t.ino),
                mimeType = null,
            )
        }
    }

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? {
        val fp = libraryApi.getAudiobookFingerprint(
            config.baseUrl, itemId, config.token, config.insecureAllowed,
        ).unwrap() ?: return null
        return CatalogAudioFingerprint(
            itemId = itemId,
            fileSizeBytes = fp.fileSizeBytes,
            totalDurationSec = fp.durationSec,
            trackDurations = fp.trackDurationsSec,
        )
    }

    override fun buildStreamUrl(itemId: String, trackIno: String): String {
        val base = AbsAudioUrl.track(config.baseUrl, itemId, trackIno)
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}token=${config.token}"
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        val detail = when (
            val r = libraryApi.getItemDetail(config.baseUrl, itemId, config.token, config.insecureAllowed)
        ) {
            is NetworkResult.Success -> r.value
            else -> return emptyList()
        }
        return detail.media.chapters.mapIndexed { i, c ->
            CatalogAudiobookChapter(index = i, startSec = c.startSec, endSec = c.endSec, title = c.title)
        }
    }

    // iOS audiobook playback goes through IosAbsAudiobookRepository, not via this method.
    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? = null

    // endregion

    internal companion object
}
