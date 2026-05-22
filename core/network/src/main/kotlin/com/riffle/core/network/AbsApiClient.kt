package com.riffle.core.network

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.network.model.AbsCollectionsResponse
import com.riffle.core.network.model.AbsEbookProgressRequest
import com.riffle.core.network.model.AbsItemResponse
import com.riffle.core.network.model.AbsProgressResponse
import com.riffle.core.network.model.AbsLibrariesResponse
import com.riffle.core.network.model.AbsLibraryItemsResponse
import com.riffle.core.network.model.AbsLoginRequest
import com.riffle.core.network.model.AbsLoginResponse
import com.riffle.core.network.model.AbsSeriesResponse
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
    data class Success(val userId: String, val token: String) : NetworkLoginResult()
    data class WrongCredentials(val message: String) : NetworkLoginResult()
    data class NetworkError(val cause: Throwable) : NetworkLoginResult()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkLoginResult()
}

class AbsApiClient(private val httpClient: OkHttpClient) : AbsApi, AbsLibraryApi, AbsSessionApi {

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
                    NetworkLoginResult.Success(userId = parsed.user.id, token = parsed.user.token)
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
            val raw = response.body?.string() ?: return@withContext NetworkLibraryItemsResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<AbsLibraryItemsResponse>(raw)
            NetworkLibraryItemsResult.Success(parsed.results.map { dto ->
                val progress = dto.userMediaProgress?.ebookProgress
                    ?: dto.userMediaProgress?.progress
                    ?: 0f
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
                )
            })
        } catch (e: IOException) {
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
                            ?: 0f
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
            NetworkCollectionResult.Success(parsed.results.map { dto ->
                NetworkCollection(
                    id = dto.id,
                    libraryId = dto.libraryId,
                    name = dto.name,
                    items = dto.books.map { book ->
                        val progress = book.userMediaProgress?.ebookProgress
                            ?: book.userMediaProgress?.progress
                            ?: 0f
                        NetworkLibraryItem(
                            id = book.id,
                            libraryId = book.libraryId,
                            title = book.media.metadata.title,
                            author = book.media.metadata.authorName,
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
            NetworkCollectionResult.NetworkError(e)
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
                val raw = response.body?.string() ?: return@withContext NetworkGetProgressResult.NetworkError(
                    IOException("Empty response body")
                )
                val parsed = json.decodeFromString<AbsProgressResponse>(raw)
                NetworkGetProgressResult.Success(NetworkServerProgress(parsed.ebookLocation, parsed.lastUpdate))
            } else NetworkGetProgressResult.NetworkError(IOException("HTTP ${response.code}"))
        } catch (e: IOException) {
            NetworkGetProgressResult.NetworkError(e)
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
