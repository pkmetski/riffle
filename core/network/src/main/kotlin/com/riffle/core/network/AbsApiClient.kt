package com.riffle.core.network

import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.models.EbookFormat
import com.riffle.core.network.model.AbsCollectionBookRequest
import com.riffle.core.network.model.AbsCollectionsResponse
import com.riffle.core.network.model.AbsItemDetailResponse
import com.riffle.core.network.model.AbsCreateCollectionRequest
import com.riffle.core.network.model.AbsCreatePlaylistRequest
import com.riffle.core.network.model.AbsAudiobookProgressRequest
import com.riffle.core.network.model.AbsEbookProgressRequest
import com.riffle.core.network.model.AbsItemResponse
import com.riffle.core.network.model.AbsLibrariesResponse
import com.riffle.core.network.model.AbsLibraryItemsResponse
import com.riffle.core.network.model.AbsLibrarySearchResponse
import com.riffle.core.network.model.AbsListeningStatsResponse
import com.riffle.core.network.model.AbsLoginRequest
import com.riffle.core.network.model.AbsLoginResponse
import com.riffle.core.network.model.AbsMeResponse
import com.riffle.core.network.model.AbsPlayDeviceInfo
import com.riffle.core.network.model.AbsPlayRequest
import com.riffle.core.network.model.AbsPlaySessionResponse
import com.riffle.core.network.model.AbsPlaylistItemRequest
import com.riffle.core.network.model.AbsPlaylistsResponse
import com.riffle.core.network.model.AbsProgressResponse
import com.riffle.core.network.model.AbsSeriesResponse
import com.riffle.core.network.model.AbsServerInfoResponse
import com.riffle.core.network.model.toNetworkCollection
import com.riffle.core.network.model.toNetworkPlaylist
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class AbsApiClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) : AbsApi, AbsLibraryApi, AbsSessionApi, AbsServerInfoApi, AbsPlaybackApi, AbsBookmarkApi {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkLoginUser> = OkHttpClassifier.classify(dispatchers.io) {
        val client = client(insecureAllowed)
        val body = json.encodeToString(AbsLoginRequest(username, password)).toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl/login").post(body).build()
        val response = client.newCall(request).execute()
        when (response.code) {
            200 -> {
                val raw = response.requireBody()
                val parsed = json.decodeFromString<AbsLoginResponse>(raw)
                NetworkLoginUser(userId = parsed.user.id, token = parsed.user.token, username = parsed.user.username)
            }
            // 401 historically surfaced as WrongCredentials; the classifier maps Auth ⇒ wrong creds.
            else -> {
                response.body?.close()
                throw HttpException(response.code, response.message)
            }
        }
    }

    override suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibrary>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/libraries", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        json.decodeFromString<AbsLibrariesResponse>(raw).libraries.map { dto ->
            NetworkLibrary(
                id = dto.id,
                name = dto.name,
                mediaType = dto.mediaType,
                audiobooksOnly = dto.settings.audiobooksOnly,
            )
        }
    }

    override suspend fun getUserProgress(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Map<String, NetworkUserMediaProgress>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/me", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        val parsed = json.decodeFromString<AbsMeResponse>(raw)
        parsed.mediaProgress
            .filter { it.libraryItemId.isNotEmpty() }
            .associate {
                it.libraryItemId to NetworkUserMediaProgress(
                    // ABS reports completion in two fields: an ebook carries a precise
                    // `ebookProgress` (CFI-based, can exceed `progress`); an audiobook carries
                    // `progress` (currentTime/duration) with `ebookProgress` 0/absent. Prefer a
                    // real (>0) `ebookProgress`, else fall back to `progress` — so a 0
                    // `ebookProgress` no longer shadows a real audiobook position (ADR 0029).
                    ebookProgress = it.ebookProgress?.takeIf { p -> p > 0f } ?: it.progress,
                    lastUpdate = it.lastUpdate,
                    finishedAt = it.finishedAt,
                )
            }
    }

    override suspend fun getLibraryItems(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/libraries/$libraryId/items", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        val parsed = json.decodeFromString<AbsLibraryItemsResponse>(raw)
        parsed.results.map { it.toNetworkLibraryItem() }
    }

    private fun AbsLibraryItemsResponse.AbsLibraryItemDto.toNetworkLibraryItem(
        fallbackLibraryId: String = "",
    ): NetworkLibraryItem {
        // Prefer a real (>0) ebook position, else the audiobook `progress`; a 0 `ebookProgress`
        // must not shadow a real audiobook position (ADR 0029). Null when no progress record.
        val progress = userMediaProgress?.let { it.ebookProgress?.takeIf { p -> p > 0f } ?: it.progress }
        return NetworkLibraryItem(
            id = id,
            libraryId = libraryId.ifEmpty { fallbackLibraryId },
            title = media.metadata.title,
            author = media.metadata.authorName,
            readingProgress = progress,
            ebookFormat = EbookFormat.from(media.ebookFormat),
            ebookFileIno = media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
            hasAudio = media.hasAudio,
            audioDurationSec = media.audioDurationSec,
            description = media.metadata.description,
            seriesName = media.metadata.seriesName,
            publishedYear = media.metadata.publishedYear,
            genres = media.metadata.genres,
            publisher = media.metadata.publisher,
            language = media.metadata.language,
            addedAt = addedAt,
            updatedAt = updatedAt,
            isbn = media.metadata.isbn,
            asin = media.metadata.asin,
        )
    }

    override suspend fun searchLibrary(
        baseUrl: String,
        libraryId: String,
        query: String,
        limit: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> = OkHttpClassifier.classify(dispatchers.io) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val response = get(baseUrl, "/api/libraries/$libraryId/search?q=$encoded&limit=$limit", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        val parsed = json.decodeFromString<AbsLibrarySearchResponse>(raw)
        parsed.book.map { it.libraryItem.toNetworkLibraryItem(libraryId) }
    }

    override suspend fun getSeries(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkSeries>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/libraries/$libraryId/series?limit=500", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        val parsed = json.decodeFromString<AbsSeriesResponse>(raw)
        parsed.results.map { dto ->
            NetworkSeries(
                id = dto.id,
                libraryId = dto.libraryId.ifEmpty { libraryId },
                name = dto.name,
                items = dto.books.map { book ->
                    val progress = book.userMediaProgress?.ebookProgress
                        ?: book.userMediaProgress?.progress
                    NetworkSeriesItem(
                        id = book.id,
                        libraryId = book.libraryId,
                        title = book.media.metadata.title,
                        author = book.media.metadata.authorName,
                        sequence = book.seriesSequence,
                        readingProgress = progress,
                        ebookFormat = EbookFormat.from(book.media.ebookFormat),
                        ebookFileIno = book.media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
                        description = book.media.metadata.description,
                        seriesName = book.media.metadata.seriesName,
                        publishedYear = book.media.metadata.publishedYear,
                        genres = book.media.metadata.genres,
                        publisher = book.media.metadata.publisher,
                        hasAudio = book.media.hasAudio,
                        audioDurationSec = book.media.audioDurationSec,
                        updatedAt = book.updatedAt,
                    )
                },
            )
        }
    }

    override suspend fun getCollections(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkCollection>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/libraries/$libraryId/collections?limit=500", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        json.decodeFromString<AbsCollectionsResponse>(raw).results.map { it.toNetworkCollection() }
    }

    override suspend fun createCollection(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> {
        val payload = AbsCreateCollectionRequest(
            libraryId = libraryId,
            name = name,
            books = listOfNotNull(initialBookId),
        )
        val body = json.encodeToString(AbsCreateCollectionRequest.serializer(), payload)
            .toRequestBody(jsonMediaType)
        return executeCollectionWrite("$baseUrl/api/collections", token, insecureAllowed) { post(body) }
    }

    override suspend fun addBookToCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> {
        val body = json.encodeToString(AbsCollectionBookRequest.serializer(), AbsCollectionBookRequest(libraryItemId))
            .toRequestBody(jsonMediaType)
        return executeCollectionWrite("$baseUrl/api/collections/$collectionId/book", token, insecureAllowed) { post(body) }
    }

    override suspend fun removeBookFromCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> = executeCollectionWrite(
        "$baseUrl/api/collections/$collectionId/book/$libraryItemId", token, insecureAllowed,
    ) { delete() }

    private suspend fun executeCollectionWrite(
        url: String,
        token: String,
        insecureAllowed: Boolean,
        buildRequest: Request.Builder.() -> Unit,
    ): NetworkResult<NetworkCollection?> = OkHttpClassifier.classify(dispatchers.io) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .apply { buildRequest() }
            .build()
        val response = client(insecureAllowed).newCall(request).execute().requireSuccessful()
        val raw = response.body?.string().orEmpty()
        if (raw.isBlank()) null
        else json.decodeFromString(AbsCollectionsResponse.AbsCollectionDto.serializer(), raw).toNetworkCollection()
    }

    override suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkPlaylist>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/libraries/$libraryId/playlists?limit=500", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        json.decodeFromString<AbsPlaylistsResponse>(raw).results.map { it.toNetworkPlaylist() }
    }

    override suspend fun createPlaylist(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> {
        val payload = AbsCreatePlaylistRequest(
            libraryId = libraryId,
            name = name,
            items = listOfNotNull(initialBookId?.let { AbsPlaylistItemRequest(it) }),
        )
        val body = json.encodeToString(AbsCreatePlaylistRequest.serializer(), payload)
            .toRequestBody(jsonMediaType)
        return executePlaylistWrite("$baseUrl/api/playlists", token, insecureAllowed) { post(body) }
    }

    override suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> {
        val body = json.encodeToString(
            AbsPlaylistItemRequest.serializer(),
            AbsPlaylistItemRequest(libraryItemId),
        ).toRequestBody(jsonMediaType)
        return executePlaylistWrite("$baseUrl/api/playlists/$playlistId/item", token, insecureAllowed) { post(body) }
    }

    override suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> = executePlaylistWrite(
        "$baseUrl/api/playlists/$playlistId/item/$libraryItemId", token, insecureAllowed,
    ) { delete() }

    private suspend fun executePlaylistWrite(
        url: String,
        token: String,
        insecureAllowed: Boolean,
        buildRequest: Request.Builder.() -> Unit,
    ): NetworkResult<NetworkPlaylist?> = OkHttpClassifier.classify(dispatchers.io) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .apply { buildRequest() }
            .build()
        val response = client(insecureAllowed).newCall(request).execute().requireSuccessful()
        val raw = response.body?.string().orEmpty()
        if (raw.isBlank()) null
        else json.decodeFromString(AbsPlaylistsResponse.AbsPlaylistDto.serializer(), raw).toNetworkPlaylist()
    }

    override suspend fun getItemEbookFileIno(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<String> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/items/$itemId", token, insecureAllowed).requireSuccessful()
        val raw = response.requireBody()
        json.decodeFromString<AbsItemResponse>(raw).media.ebookFile?.ino?.takeIf { it.isNotEmpty() }
            ?: throw IOException("No ebookFile.ino in item $itemId")
    }

    override suspend fun getAudiobookFingerprint(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = OkHttpClassifier.classify(dispatchers.io) {
        get(baseUrl, "/api/items/$itemId?expanded=1", token, insecureAllowed).use { response ->
            response.requireSuccessful()
            val raw = response.requireBody()
            // Success(null) replaces the old NoAudiobook variant.
            json.decodeFromString<AbsItemResponse>(raw).audiobookFingerprint()
        }
    }

    override suspend fun getAudiobookTracks(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsAudioTrack>> = OkHttpClassifier.classify(dispatchers.io) {
        get(baseUrl, "/api/items/$itemId?expanded=1", token, insecureAllowed).use { response ->
            response.requireSuccessful()
            val raw = response.requireBody()
            // Empty list replaces the old NoAudiobook variant.
            json.decodeFromString<AbsItemResponse>(raw).audiobookTracks()
        }
    }

    override suspend fun downloadEpub(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<ResponseBody> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/items/$itemId/ebook/$fileIno", token, insecureAllowed)
        val body = response.body ?: throw IOException("Empty response body")
        if (!response.isSuccessful) {
            body.close()
            throw HttpException(response.code, response.message)
        }
        body
    }

    override suspend fun getItemDetail(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AbsItemDetailResponse> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/items/$itemId", token, insecureAllowed).requireSuccessful()
        json.decodeFromString<AbsItemDetailResponse>(response.requireBody())
    }

    override suspend fun syncEbookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkEbookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long> = OkHttpClassifier.classify(dispatchers.io) {
        val body = json.encodeToString(AbsEbookProgressRequest(payload.ebookLocation, payload.ebookProgress, payload.isFinished))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/progress/$libraryItemId")
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()
        val response = client(insecureAllowed).newCall(request).execute().requireSuccessful()
        response.body?.string()
            ?.let { runCatching { json.decodeFromString<AbsProgressResponse>(it) }.getOrNull() }
            ?.lastUpdate ?: 0L
    }

    override suspend fun syncAudiobookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkAudiobookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long> = OkHttpClassifier.classify(dispatchers.io) {
        val progress = if (payload.duration > 0.0) (payload.currentTime / payload.duration).coerceIn(0.0, 1.0) else 0.0
        val body = json.encodeToString(AbsAudiobookProgressRequest(payload.currentTime, payload.duration, progress))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/progress/$libraryItemId")
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()
        val response = client(insecureAllowed).newCall(request).execute().requireSuccessful()
        response.body?.string()
            ?.let { runCatching { json.decodeFromString<AbsProgressResponse>(it) }.getOrNull() }
            ?.lastUpdate ?: 0L
    }

    override suspend fun getProgress(
        baseUrl: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkServerProgress> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/me/progress/$libraryItemId", token, insecureAllowed)
        // A 404 means "no progress record yet" — synthesize an empty record so callers don't
        // have to special-case `ServerError(404)`.
        if (response.code == 404) {
            response.body?.close()
            NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)
        } else {
            val raw = response.requireSuccessful().requireBody()
            val parsed = json.decodeFromString<AbsProgressResponse>(raw)
            NetworkServerProgress(
                ebookLocation = parsed.ebookLocation,
                ebookProgress = parsed.ebookProgress,
                currentTime = parsed.currentTime,
                duration = parsed.duration,
                lastUpdate = parsed.lastUpdate,
            )
        }
    }

    override suspend fun openPlaybackSession(
        baseUrl: String,
        libraryItemId: String,
        deviceId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaybackSession> = OkHttpClassifier.classify(dispatchers.io) {
        val payload = AbsPlayRequest(
            deviceInfo = AbsPlayDeviceInfo(deviceId = deviceId),
            // The MIME types Media3/ExoPlayer plays directly; ABS transcodes only if none match.
            supportedMimeTypes = listOf(
                "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/x-m4a", "audio/x-m4b",
            ),
        )
        val body = json.encodeToString(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/items/$libraryItemId/play")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        val response = client(insecureAllowed).newCall(request).execute().requireSuccessful()
        val raw = response.body?.string()
        if (raw.isNullOrEmpty()) throw IOException("Empty play session response")
        val parsed = json.decodeFromString<AbsPlaySessionResponse>(raw)
        val tracks = parsed.audioTracks.map { t ->
            NetworkAudioTrack(
                index = t.index,
                startOffsetSec = t.startOffset,
                durationSec = t.duration,
                contentUrl = t.contentUrl,
                mimeType = t.mimeType,
            )
        }
        // ABS sometimes omits a top-level duration; fall back to the summed track durations.
        val duration = if (parsed.duration > 0.0) parsed.duration else tracks.sumOf { it.durationSec }
        NetworkPlaybackSession(
            sessionId = parsed.id,
            tracks = tracks,
            chapters = parsed.chapters.map { c ->
                NetworkAudioChapter(id = c.id, startSec = c.start, endSec = c.end, title = c.title)
            },
            currentTimeSec = parsed.currentTime,
            durationSec = duration,
        )
    }

    override suspend fun syncPlaybackSession(
        baseUrl: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = OkHttpClassifier.classify(dispatchers.io) {
        val body = json.encodeToString(AbsSessionSyncRequest(currentTimeSec, timeListenedSec))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/session/$sessionId/sync")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        client(insecureAllowed).newCall(request).execute().use { it.requireSuccessful() }
        Unit
    }

    override suspend fun closePlaybackSession(
        baseUrl: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = OkHttpClassifier.classify(dispatchers.io) {
        val body = json.encodeToString(AbsSessionSyncRequest(currentTimeSec, timeListenedSec))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/session/$sessionId/close")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        client(insecureAllowed).newCall(request).execute().use { it.requireSuccessful() }
        Unit
    }

    override suspend fun getItem(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkLibraryItem?> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/items/$itemId?expanded=1", token, insecureAllowed)
        if (response.code == 404) {
            response.body?.close()
            null
        } else {
            val raw = response.requireSuccessful().requireBody()
            json.decodeFromString<AbsLibraryItemsResponse.AbsLibraryItemDto>(raw).toNetworkLibraryItem()
        }
    }

    @Serializable
    private data class AbsSessionSyncRequest(
        val currentTime: Double,
        val timeListened: Double,
    )

    override suspend fun getServerInfo(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String? {
        // `/status` is unauthenticated and returns `{ serverVersion, app, isInit, ... }`.
        // The previously-targeted `/api/server-info` does not exist on ABS (404 even with auth).
        val result = OkHttpClassifier.classify(dispatchers.io) {
            val response = client(insecureAllowed).newCall(
                Request.Builder().url("$baseUrl/status").get().build()
            ).execute().requireSuccessful()
            json.decodeFromString<AbsServerInfoResponse>(response.requireBody()).serverVersion
        }
        return result.getOrNull()
    }

    override suspend fun getCurrentUserId(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String? {
        val result = OkHttpClassifier.classify(dispatchers.io) {
            val response = get(baseUrl, "/api/me", token, insecureAllowed).requireSuccessful()
            json.decodeFromString<AbsMeResponse>(response.requireBody()).id.takeIf { it.isNotBlank() }
        }
        return result.getOrNull()
    }

    override suspend fun getListeningStats(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkListeningStats> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/me/listening-stats", token, insecureAllowed)
        val raw = response.requireSuccessful().requireBody()
        val parsed = json.decodeFromString<AbsListeningStatsResponse>(raw)
        NetworkListeningStats(totalTimeSec = parsed.totalTime)
    }

    override suspend fun createBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = OkHttpClassifier.classify(dispatchers.io) {
        val body = json.encodeToString(AbsBookmarkRequest(timeSec, title)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/item/$itemId/bookmark")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        executeBookmarkWrite(request, insecureAllowed)
    }

    override suspend fun updateBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = OkHttpClassifier.classify(dispatchers.io) {
        val body = json.encodeToString(AbsBookmarkRequest(timeSec, title)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/item/$itemId/bookmark")
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()
        executeBookmarkWrite(request, insecureAllowed)
    }

    private fun executeBookmarkWrite(request: Request, insecureAllowed: Boolean): NetworkAbsBookmark {
        val response = client(insecureAllowed).newCall(request).execute().requireSuccessful()
        val raw = response.requireBody()
        return json.decodeFromString<AbsBookmarkJson>(raw).toNetworkAbsBookmark()
    }

    override suspend fun deleteBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = OkHttpClassifier.classify(dispatchers.io) {
        val request = Request.Builder()
            .url("$baseUrl/api/me/item/$itemId/bookmark/$timeSec")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        val response = client(insecureAllowed).newCall(request).execute()
        response.body?.close()
        // Deleting an already-absent bookmark is success (idempotent) — otherwise a
        // delete-tombstone for a bookmark already gone on the server stays dirty forever.
        if (response.code == 404 || response.isSuccessful) {
            // DELETE returns plain-text "OK" with no JSON body, so synthesize the bookmark
            // from the request inputs (identity is libraryItemId + time).
            NetworkAbsBookmark(libraryItemId = itemId, title = "", timeSec = timeSec, createdAt = 0L)
        } else {
            throw HttpException(response.code, response.message)
        }
    }

    override suspend fun listBookmarks(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>> = OkHttpClassifier.classify(dispatchers.io) {
        val response = get(baseUrl, "/api/me", token, insecureAllowed).requireSuccessful()
        val raw = response.requireBody()
        // `/api/me` carries many fields; `json` is configured with ignoreUnknownKeys so the
        // wrapper need only declare `bookmarks` (absent → empty via the default).
        json.decodeFromString<AbsMeBookmarksResponse>(raw).bookmarks.map { it.toNetworkAbsBookmark() }
    }

    private fun client(insecureAllowed: Boolean): OkHttpClient =
        if (insecureAllowed) httpClient.withInsecureTls() else httpClient

    private fun get(baseUrl: String, path: String, token: String, insecureAllowed: Boolean): Response {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return client(insecureAllowed).newCall(request).execute()
    }
}

@Serializable
private data class AbsBookmarkRequest(
    @SerialName("time") val time: Int,
    @SerialName("title") val title: String,
)

@Serializable
private data class AbsBookmarkJson(
    val libraryItemId: String = "",
    @SerialName("time") val timeSec: Int = 0,
    val title: String = "",
    val createdAt: Long = 0L,
) {
    fun toNetworkAbsBookmark() = NetworkAbsBookmark(
        libraryItemId = libraryItemId,
        title = title,
        timeSec = timeSec,
        createdAt = createdAt,
    )
}

@Serializable
private data class AbsMeBookmarksResponse(
    val bookmarks: List<AbsBookmarkJson> = emptyList(),
)
