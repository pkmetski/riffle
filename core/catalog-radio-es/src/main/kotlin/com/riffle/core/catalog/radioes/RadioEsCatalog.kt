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
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.LiveStreamCapability
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.common.Clock
import com.riffle.core.common.SystemClock
import com.riffle.core.models.SourceType
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RadioEsCatalog(
    private val http: RadioEsHttpClient,
    private val apiBase: String = RadioEsParser.BASE,
    private val clock: Clock = SystemClock,
) : Catalog,
    AudiobookMediaCapability,
    DownloadsCapability,
    LiveStreamCapability,
    ToReadListCapability,
    OfflineBrowseCapability {

    override val sourceType: SourceType = SourceType.RADIO_ES

    // ---- Roots --------------------------------------------------------------

    override suspend fun listRoots(): List<CatalogRoot> = listOf(
        CatalogRoot(id = ROOT_PODCASTS, name = "Podcasts", mediaType = "audiobook"),
        CatalogRoot(id = ROOT_STATIONS, name = "Radio", mediaType = "audiobook"),
    )

    // ---- Facets -------------------------------------------------------------

    private val facetsMutex = Mutex()
    private var cachedFacets: List<CatalogFacet>? = null

    private val stationTagsMutex = Mutex()
    private var cachedCountryFacets: List<CatalogFacet>? = null
    private var cachedCountryNameBySlug: Map<String, String>? = null

    override suspend fun listFacets(rootId: String): List<CatalogFacet> = when (rootId) {
        ROOT_PODCASTS -> loadPodcastFacets()
        ROOT_STATIONS -> loadStationCountryFacets()
        else -> emptyList()
    }

    private suspend fun loadPodcastFacets(): List<CatalogFacet> {
        cachedFacets?.let { return it }
        return facetsMutex.withLock {
            cachedFacets?.let { return@withLock it }
            val body = runCatching { http.getString("$apiBase/podcasts/tags") }.getOrNull()
                ?: return@withLock emptyList()
            val tags = RadioEsParser.parseTags(body)
            val categories = tags.categories
                .filter { it.slug.isNotEmpty() }
                .mapIndexed { idx, cat ->
                    CatalogFacet(key = "slug:${cat.slug}", label = cat.name, sortOrder = idx)
                }
            val languages = tags.languages
                .filter { it.slug.isNotEmpty() }
                .mapIndexed { idx, lang ->
                    CatalogFacet(key = "lang:${lang.slug}", label = lang.name, sortOrder = categories.size + idx)
                }
            val result = categories + languages
            cachedFacets = result
            result
        }
    }

    private suspend fun loadStationCountryFacets(): List<CatalogFacet> {
        cachedCountryFacets?.let { return it }
        return stationTagsMutex.withLock {
            cachedCountryFacets?.let { return@withLock it }
            val body = runCatching { http.getString("$apiBase/stations/tags") }.getOrNull()
                ?: return@withLock emptyList()
            val countries = RadioEsParser.parseStationCountries(body)
                .filter { it.slug.isNotEmpty() }
            cachedCountryNameBySlug = countries.associate { it.slug to it.systemName }
            val facets = countries.mapIndexed { idx, c ->
                CatalogFacet(key = "country:${c.slug}", label = c.name, sortOrder = idx)
            }
            cachedCountryFacets = facets
            facets
        }
    }

    // ---- Browse -------------------------------------------------------------

    override suspend fun browse(
        rootId: String,
        sort: SortKey,
        page: Int,
        pageSize: Int,
        facet: FacetSelection?,
    ): List<CatalogItem> {
        return when (rootId) {
            ROOT_PODCASTS -> browsePodcasts(facet, page, pageSize)
            ROOT_STATIONS -> browseStations(facet, page, pageSize)
            else -> emptyList()
        }
    }

    private suspend fun browsePodcasts(facet: FacetSelection?, page: Int, pageSize: Int): List<CatalogItem> {
        val languageTag = facet?.key
            ?.takeIf { it.startsWith("lang:") }
            ?.removePrefix("lang:")
            ?.let { LANGUAGE_SLUG_TO_TAG[it] }
        val url = browseUrlFor(facet = facet, page = page, pageSize = pageSize)
        val body = http.getString(url, acceptLanguageOverride = languageTag)
        return RadioEsParser.parsePodcasts(body).podcasts
            .filter { it.playable }
            .map { it.toCatalogItem() }
    }

    private suspend fun browseStations(facet: FacetSelection?, page: Int, pageSize: Int): List<CatalogItem> {
        val offset = page * pageSize
        if (facet != null && facet.key.startsWith("country:")) {
            val slug = facet.key.removePrefix("country:")
            val countryName = cachedCountryNameBySlug?.get(slug)
                ?: slug.replaceFirstChar { it.uppercase() }
            val encoded = URLEncoder.encode(countryName, "UTF-8")
            val url = "$apiBase/stations/search?query=$encoded&count=$pageSize&offset=$offset"
            val body = http.getString(url)
            return RadioEsParser.parseStations(body).stations
                .filter { !it.streamUrl.isNullOrBlank() }
                .map { it.toCatalogItem() }
        }
        val url = "$apiBase/stations/local?count=$pageSize&offset=$offset"
        val body = http.getString(url)
        return RadioEsParser.parseStations(body).stations
            .filter { !it.streamUrl.isNullOrBlank() }
            .map { it.toCatalogItem() }
    }

    internal fun browseUrlFor(facet: FacetSelection?, page: Int, pageSize: Int): String {
        val offset = page * pageSize
        val categorySlug = when {
            facet != null && facet.key.startsWith("slug:") -> facet.key.removePrefix("slug:")
            else -> "podcasts"
        }
        // The radio.es API routes content by Accept-Language, not a query param. Append the
        // language slug to the URL so OkHttp caches language-specific responses separately
        // (the server ignores unknown params; without this the 24h disk cache would serve the
        // device-locale response to every language selection).
        val languageCacheParam = when {
            facet != null && facet.key.startsWith("lang:") -> "&_lang=${facet.key.removePrefix("lang:")}"
            else -> ""
        }
        return "$apiBase/podcasts/category/$categorySlug/charts?count=$pageSize&offset=$offset$languageCacheParam"
    }

    // ---- Search -------------------------------------------------------------

    override suspend fun search(
        rootId: String,
        query: String,
        page: Int,
        pageSize: Int,
    ): List<CatalogItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val offset = page * pageSize
        return when (rootId) {
            ROOT_PODCASTS -> {
                val url = "$apiBase/podcasts/search?query=$encoded&count=$pageSize&offset=$offset"
                val body = http.getString(url)
                RadioEsParser.parsePodcasts(body).podcasts.filter { it.playable }.map { it.toCatalogItem() }
            }
            ROOT_STATIONS -> {
                val url = "$apiBase/stations/search?query=$encoded&count=$pageSize&offset=$offset"
                val body = http.getString(url)
                RadioEsParser.parseStations(body).stations.filter { !it.streamUrl.isNullOrBlank() }.map { it.toCatalogItem() }
            }
            else -> emptyList()
        }
    }

    // ---- Item lookup --------------------------------------------------------

    override suspend fun getItem(itemId: String): CatalogItem? {
        return if (itemId.startsWith("s:")) {
            val stationId = itemId.removePrefix("s:")
            val url = "$apiBase/stations/details?stationIds=$stationId"
            val body = runCatching { http.getString(url) }.getOrNull() ?: return null
            RadioEsParser.parseStationDetail(body)?.toCatalogItem()
        } else {
            val url = "$apiBase/podcasts/details?podcastIds=$itemId"
            val body = runCatching { http.getString(url) }.getOrNull() ?: return null
            RadioEsParser.parsePodcastDetail(body)?.toCatalogItem()
        }
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
        val start = clock.nowMs()
        val ok = http.ping("$apiBase/podcasts/search?count=1&offset=0")
        return CatalogHealth(
            isReachable = ok,
            serverVersion = null,
            latencyMs = clock.nowMs() - start,
            error = if (ok) null else "prod.radio-api.net is unreachable",
        )
    }

    // ---- LiveStreamCapability -----------------------------------------------

    override fun isLiveStream(itemId: String): Boolean = itemId.startsWith("s:")

    // ---- AudiobookMediaCapability ------------------------------------------

    private suspend fun fetchEpisodes(itemId: String): List<RadioEsEpisode> {
        // stations have no episodes
        if (itemId.startsWith("s:")) return emptyList()
        val url = "$apiBase/podcasts/episodes/by-podcast-ids?podcastIds=$itemId&count=200&offset=0"
        val body = runCatching { http.getString(url) }.getOrNull() ?: return emptyList()
        // API returns newest-first; reverse to chronological order for playback
        return RadioEsParser.parseEpisodes(body).episodes.reversed()
    }

    override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> {
        if (itemId.startsWith("s:")) {
            return fetchStationTrack(itemId)
        }
        return buildTracksFromEpisodes(fetchEpisodes(itemId))
    }

    private suspend fun fetchStationTrack(itemId: String): List<CatalogAudioTrack> {
        val stationId = itemId.removePrefix("s:")
        val url = "$apiBase/stations/details?stationIds=$stationId"
        val body = runCatching { http.getString(url) }.getOrNull() ?: return emptyList()
        val station = RadioEsParser.parseStationDetail(body) ?: return emptyList()
        val streamUrl = station.streamUrl ?: return emptyList()
        return listOf(
            CatalogAudioTrack(
                ino = streamUrl,
                index = 0,
                startOffsetSec = 0.0,
                durationSec = 0.0,
                contentUrl = streamUrl,
                mimeType = station.streamFormat,
            )
        )
    }

    override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? = null

    override fun buildStreamUrl(itemId: String, trackIno: String): String = trackIno

    override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? {
        if (itemId.startsWith("s:")) {
            val tracks = fetchStationTrack(itemId)
            if (tracks.isEmpty()) return null
            return CatalogAudiobookStream(
                trackUrls = tracks.map { it.contentUrl },
                tracks = tracks,
                chapters = emptyList(),
                totalDurationSec = 0.0,
                serverCurrentTimeSec = 0.0,
                serverLastUpdate = 0L,
            )
        }
        val episodes = fetchEpisodes(itemId)
        val tracks = buildTracksFromEpisodes(episodes)
        if (tracks.isEmpty()) return null
        return CatalogAudiobookStream(
            trackUrls = tracks.map { it.contentUrl },
            tracks = tracks,
            chapters = synthesizeChaptersFromTracks(tracks, episodes.map { it.title }),
            totalDurationSec = tracks.sumOf { it.durationSec },
            serverCurrentTimeSec = 0.0,
            serverLastUpdate = 0L,
        )
    }

    override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
        if (itemId.startsWith("s:")) return emptyList()
        return runCatching {
            val episodes = fetchEpisodes(itemId)
            val tracks = buildTracksFromEpisodes(episodes)
            synthesizeChaptersFromTracks(tracks, episodes.map { it.title })
        }.getOrElse { emptyList() }
    }

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

    private fun RadioEsStation.toCatalogItem(): CatalogItem = CatalogItem(
        id = "s:$id",
        rootId = ROOT_STATIONS,
        title = name,
        author = listOfNotNull(city, country).joinToString(", "),
        coverUrl = logo300x300,
        ebookFormat = BookFormat.Audiobook,
        hasAudio = true,
        audioDurationSec = 0.0,
        description = description,
        genres = topics,
        isLiveStream = true,
    )

    companion object {
        const val ROOT_PODCASTS = "podcasts"
        const val ROOT_STATIONS = "stations"

        // Maps radio.es language slugs (from /podcasts/tags) to BCP-47 language tags for
        // the Accept-Language header. The radio.es API routes content by Accept-Language;
        // the query-string param has no effect on podcast language filtering.
        internal val LANGUAGE_SLUG_TO_TAG: Map<String, String> = mapOf(
            "arabic" to "ar",
            "chinese" to "zh",
            "danish" to "da",
            "dutch" to "nl",
            "english" to "en",
            "finnish" to "fi",
            "french" to "fr",
            "german" to "de",
            "greek" to "el",
            "italian" to "it",
            "japanese" to "ja",
            "korean" to "ko",
            "norwegian" to "no",
            "polish" to "pl",
            "portuguese" to "pt",
            "romanian" to "ro",
            "russian" to "ru",
            "spanish" to "es",
            "swedish" to "sv",
            "thai" to "th",
            "turkish" to "tr",
            "vietnamese" to "vi",
        )

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
