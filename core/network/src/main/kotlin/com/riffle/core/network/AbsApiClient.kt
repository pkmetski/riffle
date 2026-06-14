package com.riffle.core.network

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.network.model.AbsCollectionBookRequest
import com.riffle.core.network.model.AbsCollectionsResponse
import com.riffle.core.network.model.AbsCreateCollectionRequest
import com.riffle.core.network.model.AbsCreatePlaylistRequest
import com.riffle.core.network.model.AbsAudiobookProgressRequest
import com.riffle.core.network.model.AbsEbookProgressRequest
import com.riffle.core.network.model.AbsItemResponse
import com.riffle.core.network.model.AbsLibrariesResponse
import com.riffle.core.network.model.AbsLibraryItemsResponse
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class NetworkLoginResult {
    data class Success(val userId: String, val token: String, val username: String) : NetworkLoginResult()
    data class WrongCredentials(val message: String) : NetworkLoginResult()
    data class NetworkError(val cause: Throwable) : NetworkLoginResult()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkLoginResult()
}

class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi, AbsLibraryApi, AbsSessionApi, AbsServerInfoApi, AbsPlaybackApi, AbsBookmarkApi {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkLoginResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = json.encodeToString(AbsLoginRequest(username, password)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/login")
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val raw = response.body?.string() ?: return@withContext NetworkLoginResult.NetworkError(
                        IOException("Empty response body")
                    )
                    val parsed = json.decodeFromString<AbsLoginResponse>(raw)
                    NetworkLoginResult.Success(userId = parsed.user.id, token = parsed.user.token, username = parsed.user.username)
                }
                401 -> NetworkLoginResult.WrongCredentials("Invalid username or password")
                else -> NetworkLoginResult.NetworkError(IOException("Unexpected HTTP ${response.code}"))
            }
        } catch (e: SSLHandshakeException) {
            NetworkLoginResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            NetworkLoginResult.NetworkError(e)
        }
    }

    override suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkLibrariesResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/libraries")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkLibrariesResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsLibrariesResponse>(raw)
            NetworkLibrariesResult.Success(parsed.libraries.map { dto ->
                NetworkLibrary(
                    id = dto.id,
                    name = dto.name,
                    mediaType = dto.mediaType,
                    audiobooksOnly = dto.settings.audiobooksOnly,
                )
            })
        } catch (e: IOException) {
            NetworkLibrariesResult.NetworkError(e)
        }
    }

    override suspend fun getUserProgress(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkUserProgressResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/me")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkUserProgressResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsMeResponse>(raw)
            val byItemId = parsed.mediaProgress
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
                    )
                }
            NetworkUserProgressResult.Success(byItemId)
        } catch (e: Exception) {
            NetworkUserProgressResult.NetworkError(e)
        }
    }

    override suspend fun getLibraryItems(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkLibraryItemsResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/libraries/$libraryId/items")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.body?.close()
                return@withContext NetworkLibraryItemsResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val raw = response.body?.string() ?: return@withContext NetworkLibraryItemsResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsLibraryItemsResponse>(raw)
            NetworkLibraryItemsResult.Success(parsed.results.map { dto ->
                // Prefer a real (>0) ebook position, else the audiobook `progress`; a 0 `ebookProgress`
                // must not shadow a real audiobook position (ADR 0029). Null when no progress record.
                val progress = dto.userMediaProgress?.let { it.ebookProgress?.takeIf { p -> p > 0f } ?: it.progress }
                NetworkLibraryItem(
                    id = dto.id,
                    libraryId = dto.libraryId,
                    title = dto.media.metadata.title,
                    author = dto.media.metadata.authorName,
                    readingProgress = progress,
                    ebookFormat = EbookFormat.from(dto.media.ebookFormat),
                    ebookFileIno = dto.media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
                    hasAudio = dto.media.hasAudio,
                    audioDurationSec = dto.media.audioDurationSec,
                    description = dto.media.metadata.description,
                    seriesName = dto.media.metadata.seriesName,
                    publishedYear = dto.media.metadata.publishedYear,
                    genres = dto.media.metadata.genres,
                    publisher = dto.media.metadata.publisher,
                    language = dto.media.metadata.language,
                    addedAt = dto.addedAt,
                    isbn = dto.media.metadata.isbn,
                    asin = dto.media.metadata.asin,
                )
            })
        } catch (e: Exception) {
            NetworkLibraryItemsResult.NetworkError(e)
        }
    }

    override suspend fun getSeries(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSeriesResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/libraries/$libraryId/series?limit=500")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkSeriesResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsSeriesResponse>(raw)
            NetworkSeriesResult.Success(parsed.results.map { dto ->
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
                        )
                    },
                )
            })
        } catch (e: Exception) {
            NetworkSeriesResult.NetworkError(e)
        }
    }

    override suspend fun getCollections(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/libraries/$libraryId/collections?limit=500")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkCollectionResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsCollectionsResponse>(raw)
            NetworkCollectionResult.Success(parsed.results.map { it.toNetworkCollection() })
        } catch (e: Exception) {
            NetworkCollectionResult.NetworkError(e)
        }
    }

    override suspend fun createCollection(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        val payload = AbsCreateCollectionRequest(
            libraryId = libraryId,
            name = name,
            books = listOfNotNull(initialBookId),
        )
        val body = json.encodeToString(AbsCreateCollectionRequest.serializer(), payload)
            .toRequestBody(jsonMediaType)
        return executeCollectionWrite(
            url = "$baseUrl/api/collections",
            token = token,
            insecureAllowed = insecureAllowed,
        ) { post(body) }
    }

    override suspend fun addBookToCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        val body = json.encodeToString(AbsCollectionBookRequest.serializer(), AbsCollectionBookRequest(libraryItemId))
            .toRequestBody(jsonMediaType)
        return executeCollectionWrite(
            url = "$baseUrl/api/collections/$collectionId/book",
            token = token,
            insecureAllowed = insecureAllowed,
        ) { post(body) }
    }

    override suspend fun removeBookFromCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult = executeCollectionWrite(
        url = "$baseUrl/api/collections/$collectionId/book/$libraryItemId",
        token = token,
        insecureAllowed = insecureAllowed,
    ) { delete() }

    private suspend fun executeCollectionWrite(
        url: String,
        token: String,
        insecureAllowed: Boolean,
        buildRequest: Request.Builder.() -> Unit,
    ): NetworkCollectionWriteResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .apply { buildRequest() }
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext NetworkCollectionWriteResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val collection = if (raw.isBlank()) {
                null
            } else {
                json.decodeFromString(AbsCollectionsResponse.AbsCollectionDto.serializer(), raw).toNetworkCollection()
            }
            NetworkCollectionWriteResult.Success(collection)
        } catch (e: IOException) {
            NetworkCollectionWriteResult.NetworkError(e)
        }
    }

    override suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/libraries/$libraryId/playlists?limit=500")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkPlaylistResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsPlaylistsResponse>(raw)
            NetworkPlaylistResult.Success(parsed.results.map { it.toNetworkPlaylist() })
        } catch (e: Exception) {
            NetworkPlaylistResult.NetworkError(e)
        }
    }

    override suspend fun createPlaylist(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        val payload = AbsCreatePlaylistRequest(
            libraryId = libraryId,
            name = name,
            items = listOfNotNull(initialBookId?.let { AbsPlaylistItemRequest(it) }),
        )
        val body = json.encodeToString(AbsCreatePlaylistRequest.serializer(), payload)
            .toRequestBody(jsonMediaType)
        return executePlaylistWrite(
            url = "$baseUrl/api/playlists",
            token = token,
            insecureAllowed = insecureAllowed,
        ) { post(body) }
    }

    override suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult {
        val body = json.encodeToString(
            AbsPlaylistItemRequest.serializer(),
            AbsPlaylistItemRequest(libraryItemId),
        ).toRequestBody(jsonMediaType)
        return executePlaylistWrite(
            url = "$baseUrl/api/playlists/$playlistId/item",
            token = token,
            insecureAllowed = insecureAllowed,
        ) { post(body) }
    }

    override suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = executePlaylistWrite(
        url = "$baseUrl/api/playlists/$playlistId/item/$libraryItemId",
        token = token,
        insecureAllowed = insecureAllowed,
    ) { delete() }

    private suspend fun executePlaylistWrite(
        url: String,
        token: String,
        insecureAllowed: Boolean,
        buildRequest: Request.Builder.() -> Unit,
    ): NetworkPlaylistWriteResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .apply { buildRequest() }
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext NetworkPlaylistWriteResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val playlist = if (raw.isBlank()) null else
                json.decodeFromString(AbsPlaylistsResponse.AbsPlaylistDto.serializer(), raw).toNetworkPlaylist()
            NetworkPlaylistWriteResult.Success(playlist)
        } catch (e: IOException) {
            NetworkPlaylistWriteResult.NetworkError(e)
        }
    }

    override suspend fun getItemEbookFileIno(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkItemEbookInoResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/items/$itemId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: return@withContext NetworkItemEbookInoResult.NetworkError(
                IOException("Empty response body")
            )
            if (!response.isSuccessful) {
                return@withContext NetworkItemEbookInoResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val parsed = json.decodeFromString<AbsItemResponse>(raw)
            val ino = parsed.media.ebookFile?.ino?.takeIf { it.isNotEmpty() }
                ?: return@withContext NetworkItemEbookInoResult.NetworkError(IOException("No ebookFile.ino in item $itemId"))
            NetworkItemEbookInoResult.Success(ino)
        } catch (e: IOException) {
            NetworkItemEbookInoResult.NetworkError(e)
        }
    }

    override suspend fun downloadEpub(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkEpubDownloadResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/items/$itemId/ebook/$fileIno")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body ?: return@withContext NetworkEpubDownloadResult.NetworkError(
                IOException("Empty response body")
            )
            if (response.isSuccessful) NetworkEpubDownloadResult.Success(body)
            else {
                body.close()
                NetworkEpubDownloadResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkEpubDownloadResult.NetworkError(e)
        }
    }

    override suspend fun syncEbookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkEbookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSyncSessionResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = json.encodeToString(AbsEbookProgressRequest(payload.ebookLocation, payload.ebookProgress))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/progress/$libraryItemId")
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val lastUpdate = response.body?.string()
                    ?.let { runCatching { json.decodeFromString<AbsProgressResponse>(it) }.getOrNull() }
                    ?.lastUpdate ?: 0L
                NetworkSyncSessionResult.Success(lastUpdate)
            } else NetworkSyncSessionResult.NetworkError(IOException("HTTP ${response.code}"))
        } catch (e: IOException) {
            NetworkSyncSessionResult.NetworkError(e)
        }
    }

    override suspend fun syncAudiobookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkAudiobookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSyncSessionResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val progress = if (payload.duration > 0.0) (payload.currentTime / payload.duration).coerceIn(0.0, 1.0) else 0.0
        val body = json.encodeToString(AbsAudiobookProgressRequest(payload.currentTime, payload.duration, progress))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/progress/$libraryItemId")
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val lastUpdate = response.body?.string()
                    ?.let { runCatching { json.decodeFromString<AbsProgressResponse>(it) }.getOrNull() }
                    ?.lastUpdate ?: 0L
                NetworkSyncSessionResult.Success(lastUpdate)
            } else NetworkSyncSessionResult.NetworkError(IOException("HTTP ${response.code}"))
        } catch (e: IOException) {
            NetworkSyncSessionResult.NetworkError(e)
        }
    }

    override suspend fun getProgress(
        baseUrl: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkGetProgressResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/me/progress/$libraryItemId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val raw = response.body?.string()
                if (raw.isNullOrEmpty()) return@withContext NetworkGetProgressResult.NetworkError(
                    IOException("Empty response body")
                )
                val parsed = json.decodeFromString<AbsProgressResponse>(raw)
                NetworkGetProgressResult.Success(
                    NetworkServerProgress(
                        ebookLocation = parsed.ebookLocation,
                        ebookProgress = parsed.ebookProgress,
                        currentTime = parsed.currentTime,
                        duration = parsed.duration,
                        lastUpdate = parsed.lastUpdate,
                    )
                )
            } else if (response.code == 404) {
                NetworkGetProgressResult.Success(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
            } else {
                NetworkGetProgressResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkGetProgressResult.NetworkError(e)
        }
    }

    override suspend fun openPlaybackSession(
        baseUrl: String,
        libraryItemId: String,
        deviceId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaybackSessionResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
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
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext NetworkPlaybackSessionResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val raw = response.body?.string()
            if (raw.isNullOrEmpty()) {
                return@withContext NetworkPlaybackSessionResult.NetworkError(IOException("Empty play session response"))
            }
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
            NetworkPlaybackSessionResult.Success(
                NetworkPlaybackSession(
                    sessionId = parsed.id,
                    tracks = tracks,
                    chapters = parsed.chapters.map { c ->
                        NetworkAudioChapter(id = c.id, startSec = c.start, endSec = c.end, title = c.title)
                    },
                    currentTimeSec = parsed.currentTime,
                    durationSec = duration,
                )
            )
        } catch (e: IOException) {
            NetworkPlaybackSessionResult.NetworkError(e)
        }
    }

    override suspend fun getServerInfo(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        // `/status` is unauthenticated and returns `{ serverVersion, app, isInit, ... }`.
        // The previously-targeted `/api/server-info` does not exist on ABS (404 even with auth).
        val request = Request.Builder()
            .url("$baseUrl/status")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val raw = response.body?.string() ?: return@withContext null
            json.decodeFromString<AbsServerInfoResponse>(raw).serverVersion
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun createBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = json.encodeToString(AbsBookmarkRequest(timeSec, title)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/item/$itemId/bookmark")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        executeBookmarkWrite(client, request)
    }

    override suspend fun updateBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = json.encodeToString(AbsBookmarkRequest(timeSec, title)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/me/item/$itemId/bookmark")
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()
        executeBookmarkWrite(client, request)
    }

    private fun executeBookmarkWrite(client: OkHttpClient, request: Request): AbsBookmarkResult {
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.body?.close()
                return AbsBookmarkResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val raw = response.body?.string()
            if (raw.isNullOrEmpty()) {
                return AbsBookmarkResult.NetworkError(IOException("Empty response body"))
            }
            AbsBookmarkResult.Success(json.decodeFromString<AbsBookmarkJson>(raw).toNetworkAbsBookmark())
        } catch (e: IOException) {
            AbsBookmarkResult.NetworkError(e)
        }
    }

    override suspend fun deleteBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/me/item/$itemId/bookmark/$timeSec")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        try {
            val response = client.newCall(request).execute()
            response.body?.close()
            if (response.code == 404) {
                // Deleting an already-absent bookmark is success (idempotent) — otherwise a
                // delete-tombstone for a bookmark already gone on the server stays dirty forever.
                return@withContext AbsBookmarkResult.Success(
                    NetworkAbsBookmark(libraryItemId = itemId, title = "", timeSec = timeSec, createdAt = 0L)
                )
            }
            if (!response.isSuccessful) {
                return@withContext AbsBookmarkResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            // DELETE returns plain-text "OK" with no JSON body, so synthesize the bookmark
            // from the request inputs (identity is libraryItemId + time).
            AbsBookmarkResult.Success(
                NetworkAbsBookmark(libraryItemId = itemId, title = "", timeSec = timeSec, createdAt = 0L)
            )
        } catch (e: IOException) {
            AbsBookmarkResult.NetworkError(e)
        }
    }

    override suspend fun listBookmarks(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkListResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/me")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.body?.close()
                return@withContext AbsBookmarkListResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val raw = response.body?.string() ?: return@withContext AbsBookmarkListResult.NetworkError(
                IOException("Empty response body")
            )
            // `/api/me` carries many fields; `json` is configured with ignoreUnknownKeys so the
            // wrapper need only declare `bookmarks` (absent → empty via the default).
            val parsed = json.decodeFromString<AbsMeBookmarksResponse>(raw)
            AbsBookmarkListResult.Success(parsed.bookmarks.map { it.toNetworkAbsBookmark() })
        } catch (e: IOException) {
            AbsBookmarkListResult.NetworkError(e)
        }
    }

    private fun OkHttpClient.trustAllCerts(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        return newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .build()
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
