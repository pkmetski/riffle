package com.riffle.core.network

import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.network.model.StorytellerBookResponse
import com.riffle.core.network.model.StorytellerLoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class StorytellerApiClient(
    private val httpClient: OkHttpClient,
) : StorytellerApi, StorytellerLibraryApi {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerLoginResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("username", username)
            .addFormDataPart("password", password)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/token")
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val raw = response.body?.string() ?: return@withContext NetworkStorytellerLoginResult.NetworkError(
                        IOException("Empty response body")
                    )
                    val parsed = json.decodeFromString<StorytellerLoginResponse>(raw)
                    NetworkStorytellerLoginResult.Success(parsed.accessToken)
                }
                400, 401, 405 -> NetworkStorytellerLoginResult.WrongCredentials("Invalid username or password")
                else -> NetworkStorytellerLoginResult.NetworkError(IOException("Unexpected HTTP ${response.code}"))
            }
        } catch (e: SSLHandshakeException) {
            NetworkStorytellerLoginResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            NetworkStorytellerLoginResult.NetworkError(e)
        }
    }

    override suspend fun validateToken(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerValidateResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/validate")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            response.body?.close()
            when (response.code) {
                in 200..299 -> NetworkStorytellerValidateResult.Valid
                401, 403 -> NetworkStorytellerValidateResult.Invalid
                else -> NetworkStorytellerValidateResult.NetworkError(IOException("Unexpected HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkStorytellerValidateResult.NetworkError(e)
        }
    }

    override suspend fun listReadalouds(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBooksResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        // ?synced=true: server-side filter to completed readalouds only (ADR 0020).
        val request = Request.Builder()
            .url("$baseUrl/api/books?synced=true")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext NetworkStorytellerBooksResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val raw = response.body?.string() ?: return@withContext NetworkStorytellerBooksResult.NetworkError(
                IOException("Empty response body")
            )
            val parsed = json.decodeFromString<List<StorytellerBookResponse>>(raw)
            NetworkStorytellerBooksResult.Success(parsed.map { it.toNetwork() })
        } catch (e: IOException) {
            NetworkStorytellerBooksResult.NetworkError(e)
        }
    }

    override suspend fun getBook(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBookResult = withContext(Dispatchers.IO) {
        val client = if (insecureAllowed) httpClient.trustAllCerts() else httpClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            when (response.code) {
                in 200..299 -> {
                    val raw = response.body?.string() ?: return@withContext NetworkStorytellerBookResult.NetworkError(
                        IOException("Empty response body")
                    )
                    val parsed = json.decodeFromString<StorytellerBookResponse>(raw)
                    NetworkStorytellerBookResult.Success(parsed.toNetwork())
                }
                404 -> NetworkStorytellerBookResult.NotFound(bookId)
                else -> NetworkStorytellerBookResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkStorytellerBookResult.NetworkError(e)
        }
    }

    override fun coverUrl(baseUrl: String, bookId: Long): String =
        "$baseUrl/api/books/$bookId/cover"

    private fun StorytellerBookResponse.toNetwork(): NetworkStorytellerBook =
        NetworkStorytellerBook(
            id = id,
            title = title,
            authors = authors.map { it.name },
        )

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
