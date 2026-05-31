package com.riffle.core.network

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.network.model.AbsCollectionBookRequest
import com.riffle.core.network.model.AbsCollectionsResponse
import com.riffle.core.network.model.AbsCreateCollectionRequest
import com.riffle.core.network.model.AbsCreatePlaylistRequest
import com.riffle.core.network.model.AbsEbookProgressRequest
import com.riffle.core.network.model.AbsItemResponse
import com.riffle.core.network.model.AbsLibrariesResponse
import com.riffle.core.network.model.AbsLibraryItemsResponse
import com.riffle.core.network.model.AbsLoginRequest
import com.riffle.core.network.model.AbsLoginResponse
import com.riffle.core.network.model.AbsMeResponse
import com.riffle.core.network.model.AbsPlaylistItemRequest
import com.riffle.core.network.model.AbsPlaylistsResponse
import com.riffle.core.network.model.AbsProgressResponse
import com.riffle.core.network.model.AbsSeriesResponse
import com.riffle.core.network.model.AbsServerInfoResponse
import com.riffle.core.network.model.toNetworkCollection
import com.riffle.core.network.model.toNetworkPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi, AbsLibraryApi, AbsSessionApi, AbsServerInfoApi {

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
                        ebookProgress = it.ebookProgress ?: it.progress,
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
                val progress = dto.userMediaProgress?.ebookProgress
                    ?: dto.userMediaProgress?.progress
                NetworkLibraryItem(
                    id = dto.id,
                    libraryId = dto.libraryId,
                    title = dto.media.metadata.title,
                    author = dto.media.metadata.authorName,
                    readingProgress = progress,
                    ebookFormat = EbookFormat.from(dto.media.ebookFormat),
                    ebookFileIno = dto.media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
                    description = dto.media.metadata.description,
                    seriesName = dto.media.metadata.seriesName,
                    publishedYear = dto.media.metadata.publishedYear,
                    genres = dto.media.metadata.genres,
                    publisher = dto.media.metadata.publisher,
                    addedAt = dto.addedAt,
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
                    NetworkServerProgress(parsed.ebookLocation, parsed.ebookProgress, parsed.lastUpdate)
                )
            } else if (response.code == 404) {
                NetworkGetProgressResult.Success(NetworkServerProgress("", 0f, 0L))
            } else {
                NetworkGetProgressResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkGetProgressResult.NetworkError(e)
        }
    }

    override suspend fun getServerInfo(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/server-info")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val raw = response.body?.string() ?: return@withContext null
            json.decodeFromString<AbsServerInfoResponse>(raw).version
        } catch (_: Exception) {
            null
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
