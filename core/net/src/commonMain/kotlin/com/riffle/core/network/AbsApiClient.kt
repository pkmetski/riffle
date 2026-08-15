package com.riffle.core.network

import com.riffle.core.models.AudiobookFingerprint
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
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

class AbsApiClient(
    private val httpClient: HttpClient,
) : AbsApi, AbsLibraryApi, AbsSessionApi, AbsServerInfoApi, AbsPlaybackApi, AbsBookmarkApi {

    override suspend fun uploadBook(
        baseUrl: String,
        libraryId: String,
        metadata: NetworkUploadMetadata,
        files: List<NetworkUploadPart>,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/api/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData {
                append("title", metadata.title)
                append("author", metadata.author)
                append("library", libraryId)
                metadata.folderId?.let { append("folder", it) }
                metadata.series?.let { append("series", it) }
                metadata.description?.let { append("description", it) }
                metadata.publisher?.let { append("publisher", it) }
                metadata.language?.let { append("language", it) }
                metadata.publishedYear?.let { append("publishedYear", it) }
                metadata.isbn?.let { append("isbn", it) }
                metadata.asin?.let { append("asin", it) }
                if (metadata.genres.isNotEmpty()) append("genres", metadata.genres.joinToString(","))
                files.forEach { file ->
                    append(
                        "files",
                        ChannelProvider(file.sizeBytes) { file.provider() },
                        Headers.build {
                            append(HttpHeaders.ContentType, file.mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.fileName}\"")
                        },
                    )
                }
            }))
        }
        if (!response.status.isSuccess()) {
            throw HttpException(response.status.value, response.status.description)
        }
    }

    override suspend fun updateItemMedia(
        baseUrl: String,
        itemId: String,
        metadata: NetworkAbsMetadataUpdate,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = KtorClassifier.classify {
        val response = client(insecureAllowed).patch("$baseUrl/api/items/$itemId/media") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(NetworkAbsMediaUpdate(metadata))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
    }

    override suspend fun updateItemChapters(
        baseUrl: String,
        itemId: String,
        chapters: List<NetworkAbsChapterUpdate>,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/api/items/$itemId/chapters") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(NetworkAbsChaptersUpdate(chapters))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
    }

    override suspend fun uploadItemCoverFromUrl(
        baseUrl: String,
        itemId: String,
        url: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/api/items/$itemId/cover") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(NetworkAbsCoverUrlUpdate(url))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
    }

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkLoginUser> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/login") {
            contentType(ContentType.Application.Json)
            setBody(AbsLoginRequest(username, password))
        }
        when (response.status.value) {
            200 -> {
                val parsed = response.body<AbsLoginResponse>()
                NetworkLoginUser(userId = parsed.user.id, token = parsed.user.token, username = parsed.user.username)
            }
            // 401 historically surfaced as WrongCredentials; the classifier maps Auth ⇒ wrong creds.
            else -> throw HttpException(response.status.value, response.status.description)
        }
    }

    override suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibrary>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsLibrariesResponse>().libraries.map { dto ->
            NetworkLibrary(
                id = dto.id,
                name = dto.name,
                mediaType = dto.mediaType,
                audiobooksOnly = dto.settings.audiobooksOnly,
                folders = dto.folders.map { folder ->
                    NetworkLibraryFolder(id = folder.id, fullPath = folder.fullPath)
                },
            )
        }
    }

    override suspend fun getUserProgress(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Map<String, NetworkUserMediaProgress>> = KtorClassifier.classify {
        val parsed = client(insecureAllowed).get("$baseUrl/api/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsMeResponse>()
        parsed.mediaProgress
            .filter { it.libraryItemId.isNotEmpty() }
            .associate {
                it.libraryItemId to NetworkUserMediaProgress(
                    // ABS reports completion in two fields: an ebook carries a precise
                    // `ebookProgress` (CFI-based, can exceed `progress`); an audiobook carries
                    // `currentTime`/`duration` with `ebookProgress` 0/absent. Surface a real
                    // (>0) `ebookProgress` and the raw audio position so callers derive one
                    // unified fraction (ADR 0035). Do NOT fold the stored `progress` scalar in
                    // when audio position data exists — that scalar can be stale (a client once
                    // pushed it computed against the wrong duration) and folding it made the
                    // bulk pull disagree with the per-item pull, ping-ponging the library UI.
                    // Only when the entry carries no duration at all is `progress` the sole
                    // signal left, so keep it as the last-resort fallback there.
                    ebookProgress = it.ebookProgress?.takeIf { p -> p > 0f }
                        ?: it.progress.takeIf { _ -> it.duration <= 0.0 },
                    currentTime = it.currentTime,
                    duration = it.duration,
                    isFinished = it.isFinished,
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
    ): NetworkResult<List<NetworkLibraryItem>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries/$libraryId/items") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsLibraryItemsResponse>().results.map { it.toNetworkLibraryItem() }
    }

    override suspend fun getRecentlyAddedLibraryItems(
        baseUrl: String,
        libraryId: String,
        limit: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries/$libraryId/items") {
            url {
                parameters.append("limit", limit.toString())
                // ABS deliberately bypasses its server-side API cache for random-sorted reads.
                parameters.append("sort", "random")
                // Some ABS deployments sit behind a proxy that ignores request cache headers.
                // A unique query forces that proxy to fetch the changing library index.
                parameters.append("_riffle_refresh", Random.nextLong().toString())
            }
            // Reconciliation runs immediately after a write. Never reuse an item listing that
            // was captured before ABS finished scanning or updating the uploaded metadata.
            header(HttpHeaders.CacheControl, "no-cache, no-store")
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsLibraryItemsResponse>().results.map { it.toNetworkLibraryItem() }
    }

    override suspend fun scanLibrary(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = KtorClassifier.classify {
        // A normal scan is ignored when ABS already has a scan in flight. Uploads can land in
        // that window, so force the scan that discovers this upload instead of relying on the
        // watcher or an earlier scan to notice it.
        val response = client(insecureAllowed).post("$baseUrl/api/libraries/$libraryId/scan?force=1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw HttpException(response.status.value, response.status.description)
        }
    }

    private fun AbsLibraryItemsResponse.AbsLibraryItemDto.toNetworkLibraryItem(
        fallbackLibraryId: String = "",
    ): NetworkLibraryItem {
        // Prefer a real (>0) ebook position, else the audiobook `progress`; a 0 `ebookProgress`
        // must not shadow a real audiobook position (ADR 0035). Null when no progress record.
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
            path = path,
            relPath = relPath,
        )
    }

    override suspend fun searchLibrary(
        baseUrl: String,
        libraryId: String,
        query: String,
        limit: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries/$libraryId/search") {
            url {
                parameters.append("q", query)
                parameters.append("limit", limit.toString())
                // ABS's API cache middleware skips every request carrying sort=random, even for
                // search routes. The server ignores this parameter for search semantics.
                parameters.append("sort", "random")
                parameters.append("_riffle_refresh", Random.nextLong().toString())
            }
            header(HttpHeaders.CacheControl, "no-cache, no-store")
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsLibrarySearchResponse>().book.map { it.libraryItem.toNetworkLibraryItem(libraryId) }
    }

    override suspend fun getSeries(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkSeries>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries/$libraryId/series?limit=500") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsSeriesResponse>().results.map { dto ->
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
    ): NetworkResult<List<NetworkCollection>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries/$libraryId/collections?limit=500") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsCollectionsResponse>().results.map { it.toNetworkCollection() }
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
        return executeCollectionWrite(insecureAllowed) {
            post("$baseUrl/api/collections") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
    }

    override suspend fun addBookToCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> =
        executeCollectionWrite(insecureAllowed) {
            post("$baseUrl/api/collections/$collectionId/book") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(AbsCollectionBookRequest(libraryItemId))
            }
        }

    override suspend fun removeBookFromCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> =
        executeCollectionWrite(insecureAllowed) {
            delete("$baseUrl/api/collections/$collectionId/book/$libraryItemId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

    private suspend fun executeCollectionWrite(
        insecureAllowed: Boolean,
        makeRequest: suspend HttpClient.() -> HttpResponse,
    ): NetworkResult<NetworkCollection?> = KtorClassifier.classify {
        val response = client(insecureAllowed).makeRequest()
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        val raw = response.body<String>()
        if (raw.isBlank()) null
        else RIFFLE_JSON.decodeFromString(AbsCollectionsResponse.AbsCollectionDto.serializer(), raw).toNetworkCollection()
    }

    override suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkPlaylist>> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/libraries/$libraryId/playlists?limit=500") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsPlaylistsResponse>().results.map { it.toNetworkPlaylist() }
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
        return executePlaylistWrite(insecureAllowed) {
            post("$baseUrl/api/playlists") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
    }

    override suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> =
        executePlaylistWrite(insecureAllowed) {
            post("$baseUrl/api/playlists/$playlistId/item") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(AbsPlaylistItemRequest(libraryItemId))
            }
        }

    override suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> =
        executePlaylistWrite(insecureAllowed) {
            delete("$baseUrl/api/playlists/$playlistId/item/$libraryItemId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

    private suspend fun executePlaylistWrite(
        insecureAllowed: Boolean,
        makeRequest: suspend HttpClient.() -> HttpResponse,
    ): NetworkResult<NetworkPlaylist?> = KtorClassifier.classify {
        val response = client(insecureAllowed).makeRequest()
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        val raw = response.body<String>()
        if (raw.isBlank()) null
        else RIFFLE_JSON.decodeFromString(AbsPlaylistsResponse.AbsPlaylistDto.serializer(), raw).toNetworkPlaylist()
    }

    override suspend fun getItemEbookFileIno(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<String> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/items/$itemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsItemResponse>().media.ebookFile?.ino?.takeIf { it.isNotEmpty() }
            ?: throw newIOException("No ebookFile.ino in item $itemId")
    }

    override suspend fun getAudiobookFingerprint(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = KtorClassifier.classify {
        // Success(null) replaces the old NoAudiobook variant.
        client(insecureAllowed).get("$baseUrl/api/items/$itemId?expanded=1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsItemResponse>().audiobookFingerprint()
    }

    override suspend fun getAudiobookTracks(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsAudioTrack>> = KtorClassifier.classify {
        // Empty list replaces the old NoAudiobook variant.
        client(insecureAllowed).get("$baseUrl/api/items/$itemId?expanded=1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<AbsItemResponse>().audiobookTracks()
    }

    override suspend fun getItemDetail(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AbsItemDetailResponse> = KtorClassifier.classify {
        client(insecureAllowed).get("$baseUrl/api/items/$itemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()
    }

    override suspend fun getItem(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkLibraryItem?> = KtorClassifier.classify {
        val response = client(insecureAllowed).get("$baseUrl/api/items/$itemId?expanded=1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status == HttpStatusCode.NotFound) null
        else response.body<AbsLibraryItemsResponse.AbsLibraryItemDto>().toNetworkLibraryItem()
    }

    override suspend fun syncEbookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkEbookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long> = KtorClassifier.classify {
        val response = client(insecureAllowed).patch("$baseUrl/api/me/progress/$libraryItemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AbsEbookProgressRequest(payload.ebookLocation, payload.ebookProgress, payload.isFinished))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        runCatching { response.body<AbsProgressResponse>() }.getOrNull()?.lastUpdate ?: 0L
    }

    override suspend fun syncAudiobookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkAudiobookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long> = KtorClassifier.classify {
        val progress = if (payload.duration > 0.0) (payload.currentTime / payload.duration).coerceIn(0.0, 1.0) else 0.0
        val response = client(insecureAllowed).patch("$baseUrl/api/me/progress/$libraryItemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AbsAudiobookProgressRequest(payload.currentTime, payload.duration, progress, payload.isFinished))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        runCatching { response.body<AbsProgressResponse>() }.getOrNull()?.lastUpdate ?: 0L
    }

    override suspend fun getProgress(
        baseUrl: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkServerProgress> = KtorClassifier.classify {
        val response = client(insecureAllowed).get("$baseUrl/api/me/progress/$libraryItemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        // A 404 means "no progress record yet" — synthesize an empty record so callers don't
        // have to special-case `ServerError(404)`.
        if (response.status == HttpStatusCode.NotFound) {
            NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)
        } else {
            if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
            val parsed = response.body<AbsProgressResponse>()
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
    ): NetworkResult<NetworkPlaybackSession> = KtorClassifier.classify {
        val payload = AbsPlayRequest(
            deviceInfo = AbsPlayDeviceInfo(deviceId = deviceId),
            // The MIME types Media3/ExoPlayer plays directly; ABS transcodes only if none match.
            supportedMimeTypes = listOf(
                "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/x-m4a", "audio/x-m4b",
            ),
        )
        val response = client(insecureAllowed).post("$baseUrl/api/items/$libraryItemId/play") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        val parsed = response.body<AbsPlaySessionResponse>()
        val tracks = parsed.audioTracks.map { t ->
            NetworkAudioTrack(
                index = t.index,
                startOffsetSec = t.startOffset,
                durationSec = t.duration,
                contentUrl = t.contentUrl,
                mimeType = t.mimeType,
            )
        }
        val chapters = parsed.chapters.map { c ->
            NetworkAudioChapter(id = c.id, startSec = c.start, endSec = c.end, title = c.title)
        }
        // ABS sometimes omits a top-level duration; fall back to the summed track durations.
        val duration = if (parsed.duration > 0.0) parsed.duration else tracks.sumOf { it.durationSec }
        NetworkPlaybackSession(
            sessionId = parsed.id,
            tracks = tracks,
            chapters = chapters,
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
    ): NetworkResult<Unit> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/api/session/$sessionId/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AbsSessionSyncRequest(currentTimeSec, timeListenedSec))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
    }

    override suspend fun closePlaybackSession(
        baseUrl: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/api/session/$sessionId/close") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AbsSessionSyncRequest(currentTimeSec, timeListenedSec))
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
    }

    override suspend fun getServerInfo(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String? {
        // `/status` is unauthenticated and returns `{ serverVersion, app, isInit, ... }`.
        // The previously-targeted `/api/server-info` does not exist on ABS (404 even with auth).
        return KtorClassifier.classify {
            client(insecureAllowed).get("$baseUrl/status")
                .body<AbsServerInfoResponse>().serverVersion
        }.getOrNull()
    }

    override suspend fun getCurrentUserId(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String? {
        return KtorClassifier.classify {
            client(insecureAllowed).get("$baseUrl/api/me") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.body<AbsMeResponse>().id.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun getListeningStats(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkListeningStats> = KtorClassifier.classify {
        val response = client(insecureAllowed).get("$baseUrl/api/me/listening-stats") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        response.body<AbsListeningStatsResponse>().let { NetworkListeningStats(totalTimeSec = it.totalTime) }
    }

    override suspend fun createBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = KtorClassifier.classify {
        client(insecureAllowed).post("$baseUrl/api/me/item/$itemId/bookmark") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AbsBookmarkRequest(timeSec, title))
        }.body<AbsBookmarkJson>().toNetworkAbsBookmark()
    }

    override suspend fun updateBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = KtorClassifier.classify {
        client(insecureAllowed).patch("$baseUrl/api/me/item/$itemId/bookmark") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AbsBookmarkRequest(timeSec, title))
        }.body<AbsBookmarkJson>().toNetworkAbsBookmark()
    }

    override suspend fun deleteBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = KtorClassifier.classify {
        val response = client(insecureAllowed).delete("$baseUrl/api/me/item/$itemId/bookmark/$timeSec") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        // Deleting an already-absent bookmark is success (idempotent) — otherwise a
        // delete-tombstone for a bookmark already gone on the server stays dirty forever.
        if (response.status == HttpStatusCode.NotFound || response.status.isSuccess()) {
            // DELETE returns plain-text "OK" with no JSON body, so synthesize the bookmark
            // from the request inputs (identity is libraryItemId + time).
            NetworkAbsBookmark(libraryItemId = itemId, title = "", timeSec = timeSec, createdAt = 0L)
        } else {
            throw HttpException(response.status.value, response.status.description)
        }
    }

    override suspend fun listBookmarks(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>> = KtorClassifier.classify {
        // Bypass the shared cache for `/api/me` (15-min TTL from EndpointCacheHeadersInterceptor).
        // Annotation sync piggybacks on user bookmarks; a stale cached body would hide a peer device's
        // freshly-written bookmark until reopen. Mirrors WebDAV, whose PROPFIND was never cached.
        client(insecureAllowed).get("$baseUrl/api/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.CacheControl, "no-cache, no-store")
        }.body<AbsMeBookmarksResponse>().bookmarks.map { it.toNetworkAbsBookmark() }
    }

    private fun client(insecureAllowed: Boolean): HttpClient =
        if (insecureAllowed) httpClient.withInsecureTls() else httpClient

    @Serializable
    private data class AbsSessionSyncRequest(
        val currentTime: Double,
        val timeListened: Double,
    )
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
