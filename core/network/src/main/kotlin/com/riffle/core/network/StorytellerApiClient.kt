package com.riffle.core.network

import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.model.StorytellerBookResponse
import com.riffle.core.network.model.StorytellerLoginResponse
import com.riffle.core.network.model.StorytellerV2BookResponse
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class StorytellerApiClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) : StorytellerApi, StorytellerLibraryApi {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkResult<String> = OkHttpClassifier.classify(dispatchers.io) {
        val client = client(insecureAllowed)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("username", username)
            .addFormDataPart("password", password)
            .build()
        val request = Request.Builder().url("$baseUrl/api/token").post(body).build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val raw = response.body?.string() ?: throw IOException("Empty response body")
                    json.decodeFromString<StorytellerLoginResponse>(raw).accessToken
                }
                // 401 ⇒ Auth, but 400/405 also count as wrong creds for Storyteller.
                400, 401, 405 -> throw HttpException(401, "Invalid username or password")
                else -> throw HttpException(response.code, response.message)
            }
        }
    }

    override suspend fun validateToken(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Boolean> = OkHttpClassifier.classify(dispatchers.io) {
        val request = Request.Builder()
            .url("$baseUrl/api/validate")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client(insecureAllowed).newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> true
                401, 403 -> false
                else -> throw HttpException(response.code, response.message)
            }
        }
    }

    override suspend fun listReadalouds(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkStorytellerBook>> = OkHttpClassifier.classify(dispatchers.io) {
        // ?synced=true: server-side filter to completed readalouds only (ADR 0020).
        val request = Request.Builder()
            .url("$baseUrl/api/books?synced=true")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client(insecureAllowed).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, response.message)
            val raw = response.body?.string() ?: throw IOException("Empty response body")
            json.decodeFromString<List<StorytellerBookResponse>>(raw).map { it.toNetwork() }
        }
    }

    override suspend fun getBook(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkStorytellerBook> = OkHttpClassifier.classify(dispatchers.io) {
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client(insecureAllowed).newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> {
                    val raw = response.body?.string() ?: throw IOException("Empty response body")
                    json.decodeFromString<StorytellerBookResponse>(raw).toNetwork()
                }
                // 404 surfaces as ServerError(404) — replaces the old NotFound variant.
                else -> throw HttpException(response.code, response.message)
            }
        }
    }

    override fun coverUrl(baseUrl: String, bookId: Long): String =
        "$baseUrl/api/books/$bookId/cover"

    override suspend fun getAudiobookFingerprint(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = OkHttpClassifier.classify(dispatchers.io) {
        val request = Request.Builder()
            .url("$baseUrl/api/v2/books/$bookId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client(insecureAllowed).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, response.message)
            val raw = response.body?.string() ?: throw IOException("Empty response body")
            // Success(null) replaces the old NoAudiobook variant.
            json.decodeFromString<StorytellerV2BookResponse>(raw).toFingerprint()
        }
    }

    private fun client(insecureAllowed: Boolean): OkHttpClient =
        if (insecureAllowed) httpClient.withInsecureTls() else httpClient

    private fun StorytellerBookResponse.toNetwork(): NetworkStorytellerBook =
        NetworkStorytellerBook(
            id = id,
            title = title,
            authors = authors.map { it.name },
            isbn = isbn,
            asin = asin,
        )
}
