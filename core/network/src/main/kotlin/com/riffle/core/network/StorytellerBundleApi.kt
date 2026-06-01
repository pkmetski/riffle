package com.riffle.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface NetworkStorytellerBundleResult {
    data class Success(val body: ResponseBody) : NetworkStorytellerBundleResult
    data class NetworkError(val cause: Throwable) : NetworkStorytellerBundleResult
}

interface StorytellerBundleApi {
    suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleResult
}

class StorytellerBundleApiImpl(
    private val client: OkHttpClient,
) : StorytellerBundleApi {

    override suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleResult = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) client.trustAllCerts() else client
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            val response = effectiveClient.newCall(request).execute()
            val body = response.body ?: return@withContext NetworkStorytellerBundleResult.NetworkError(
                IOException("Empty response body")
            )
            if (response.isSuccessful) {
                NetworkStorytellerBundleResult.Success(body)
            } else {
                body.close()
                NetworkStorytellerBundleResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkStorytellerBundleResult.NetworkError(e)
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
