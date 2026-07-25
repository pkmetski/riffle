package com.riffle.core.network

import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.network.model.StorytellerBookResponse
import com.riffle.core.network.model.StorytellerLoginResponse
import com.riffle.core.network.model.StorytellerV2BookResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

class StorytellerApiClient(
    private val httpClient: HttpClient,
) : StorytellerApi, StorytellerLibraryApi {

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkResult<String> = KtorClassifier.classify {
        val response = client(insecureAllowed).post("$baseUrl/api/token") {
            setBody(MultiPartFormDataContent(formData {
                append("username", username)
                append("password", password)
            }))
        }
        when (response.status.value) {
            200 -> response.body<StorytellerLoginResponse>().accessToken
            // 401 ⇒ Auth, but 400/405 also count as wrong creds for Storyteller.
            400, 401, 405 -> throw HttpException(401, "Invalid username or password")
            else -> throw HttpException(response.status.value, response.status.description)
        }
    }

    override suspend fun validateToken(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Boolean> = KtorClassifier.classify {
        val response = client(insecureAllowed).get("$baseUrl/api/validate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        when (response.status.value) {
            in 200..299 -> true
            401, 403 -> false
            else -> throw HttpException(response.status.value, response.status.description)
        }
    }

    override suspend fun listReadalouds(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkStorytellerBook>> = KtorClassifier.classify {
        // ?synced=true: server-side filter to completed readalouds only (ADR 0020).
        val response = client(insecureAllowed).get("$baseUrl/api/books?synced=true") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        response.body<List<StorytellerBookResponse>>().map { it.toNetwork() }
    }

    override suspend fun getBook(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkStorytellerBook> = KtorClassifier.classify {
        val response = client(insecureAllowed).get("$baseUrl/api/books/$bookId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        when (response.status.value) {
            in 200..299 -> response.body<StorytellerBookResponse>().toNetwork()
            // 404 surfaces as ServerError(404) — replaces the old NotFound variant.
            else -> throw HttpException(response.status.value, response.status.description)
        }
    }

    override fun coverUrl(baseUrl: String, bookId: Long): String =
        "$baseUrl/api/books/$bookId/cover"

    override suspend fun getAudiobookFingerprint(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = KtorClassifier.classify {
        val response = client(insecureAllowed).get("$baseUrl/api/v2/books/$bookId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) throw HttpException(response.status.value, response.status.description)
        // Success(null) replaces the old NoAudiobook variant.
        response.body<StorytellerV2BookResponse>().toFingerprint()
    }

    private fun client(insecureAllowed: Boolean): HttpClient =
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
