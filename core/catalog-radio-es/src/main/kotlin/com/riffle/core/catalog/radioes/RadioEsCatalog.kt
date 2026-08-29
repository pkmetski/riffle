package com.riffle.core.catalog.radioes

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
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.models.SourceType
import java.net.URLEncoder

class RadioEsCatalog(
    private val http: RadioEsHttpClient,
    private val apiBase: String = RadioEsParser.BASE,
) : Catalog,
    AudiobookMediaCapability,
    ToReadListCapability,
    OfflineBrowseCapability {

    override val sourceType: SourceType = SourceType.RADIO_ES

    // ---- Roots --------------------------------------------------------------

    override suspend fun listRoots(): List<CatalogRoot> = listOf(
        CatalogRoot(id = ROOT_PODCASTS, name = "Podcasts", mediaType = "audiobook"),
    )

    // ---- Facets -------------------------------------------------------------

    @Volatile private var cachedFacets: List<CatalogFacet>? = null

    override suspend fun listFacets(rootId: String): List<CatalogFacet> {
        if (rootId != ROOT_PODCASTS) return emptyList()
        cachedFacets?.let { return it }
        val body = runCatching { http.getString("$apiBase/podcasts/tags") }.getOrNull()
            ?: return emptyList()
        val tags = RadioEsParser.parseTags(body)
        val result = tags.categories
            .filter { it.slug.isNotEmpty() }
            .mapIndexed { idx, cat ->
                CatalogFacet(key = "slug:${cat.slug}", label = cat.name, sortOrder = idx)
            }
        cachedFacets = result
        return result
    }

    // ---- Browse -------------------------------------------------------------

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        if (rootId != ROOT_PODCASTS) return emptyList()
        val url = browseUrlFor(facet = facet, page = page, pageSize = pageSize)
        val body = http.getString(url)
        return RadioEsParser.parsePodcasts(body).podcasts
            .filter { it.playable }
            .map { it.toCatalogItem() }
    }

    internal fun browseUrlFor(facet: FacetSelection?, page: Int, pageSize: Int): String {
        val offset = page * pageSize
        val categorySlug = when {
            facet != null && facet.key.startsWith("slug:") -> facet.key.removePrefix("slug:")
            else -> "podcasts"
        }
        return "$apiBase/podcasts/category/$categorySlug/charts?count=$pageSize&offset=$offset"
    }

    // ---- Search -------------------------------------------------------------

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        if (rootId != ROOT_PODCASTS || query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val offset = page * pageSize
        val url = "$apiBase/podcasts/search?query=$encoded&count=$pageSize&offset=$offset"
        val body = http.getString(url)
        return RadioEsParser.parsePodcasts(body).podcasts
            .filter { it.playable }
            .map { it.toCatalogItem() }
    }

    // ---- Item lookup --------------------------------------------------------

    override suspend fun getItem(itemId: String): CatalogItem? {
        val url = "$apiBase/podcasts/details?podcastIds=$itemId"
        val body = runCatching { http.getString(url) }.getOrNull() ?: return null
        val podcast = RadioEsParser.parsePodcastDetail(body) ?: return null
        return podcast.toCatalogItem()
    }

    // ---- File access (audio-only source) ------------------------------------

    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle =
        throw RadioEsException("radio.es is audio-only — use AudiobookMediaCapability per episode")

    override suspend fun <T> withFileStream(
        itemId: String,
        format: BookFormat,
        handleHint: String?,
        block: suspend (CatalogFileStream) -> T,
    ): T = throw RadioEsException("radio.es is audio-only — use AudiobookMediaCapability per episode")

    // ---- Connectivity -------------------------------------------------------

    override suspend fun connectivityCheck(): CatalogHealth {
        val start = System.currentTimeMillis()
        val ok = http.ping("$apiBase/podcasts/search?count=1&offset=0")
        return CatalogHealth(
            isReachable = ok,
            serverVersion = null,
            latencyMs = System.currentTimeMillis() - start,
            error = if (ok) null else "prod.radio-api.net is unreachable",
        )
    }

    // ---- AudiobookMediaCapability ------------------------------------------

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> {
        val url = "$apiBase/podcasts/episodes/by-podcast-ids?podcastIds=$itemId&count=200&offset=0"
        val body = runCatching { http.getString(url) }.getOrNull() ?: return emptyList()
        return buildTracksFromEpisodes(RadioEsParser.parseEpisodes(body).episodes)
    }

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? = null

    override fun buildStreamUrl(itemId: String, trackIno: String): String = trackIno

    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? {
        val tracks = getTracks(itemId)
        if (tracks.isEmpty()) return null
        return buildAudiobookStream(tracks)
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> =
        runCatching {
            val url = "$apiBase/podcasts/episodes/by-podcast-ids?podcastIds=$itemId&count=200&offset=0"
            val body = http.getString(url)
            val episodes = RadioEsParser.parseEpisodes(body).episodes
            val tracks = buildTracksFromEpisodes(episodes)
            synthesizeChaptersFromTracks(tracks, episodes.map { it.title })
        }.getOrElse { emptyList() }

    // ---- Helpers ------------------------------------------------------------

    private fun RadioEsPodcast.toCatalogItem(): CatalogItem = CatalogItem(
        id = id,
        rootId = ROOT_PODCASTS,
        title = name,
        author = author,
        coverUrl = logo300x300,
        ebookFormat = BookFormat.Audiobook,
        hasAudio = true,
        audioDurationSec = 0.0,
        description = description,
        genres = categories,
    )

    companion object {
        const val ROOT_PODCASTS = "podcasts"

        internal fun buildTracksFromEpisodes(episodes: List<RadioEsEpisode>): List<CatalogAudioTrack> {
            var cumulativeStart = 0.0
            return episodes.mapIndexed { idx, ep ->
                val dur = ep.durationSec.toDouble()
                val track = CatalogAudioTrack(
                    ino = ep.url,
                    index = idx,
                    startOffsetSec = cumulativeStart,
                    durationSec = dur,
                    contentUrl = ep.url,
                    mimeType = ep.contentFormat,
                )
                cumulativeStart += dur
                track
            }
        }

        internal fun buildAudiobookStream(tracks: List<CatalogAudioTrack>): CatalogAudiobookStream =
            CatalogAudiobookStream(
                trackUrls = tracks.map { it.contentUrl },
                tracks = tracks,
                chapters = synthesizeChaptersFromTracks(tracks, emptyList()),
                totalDurationSec = tracks.sumOf { it.durationSec },
                serverCurrentTimeSec = 0.0,
                serverLastUpdate = 0L,
            )

        internal fun synthesizeChaptersFromTracks(
            tracks: List<CatalogAudioTrack>,
            episodeTitles: List<String>,
        ): List<CatalogAudiobookChapter> = tracks.mapIndexed { i, t ->
            val title = episodeTitles.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "Episode ${i + 1}"
            CatalogAudiobookChapter(
                index = i,
                startSec = t.startOffsetSec,
                endSec = t.startOffsetSec + t.durationSec,
                title = title,
            )
        }
    }
}

internal class RadioEsException(message: String) : RuntimeException(message)
