package com.riffle.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A streaming Storyteller bundle response — caller must close [body]. */
data class StorytellerBundleStream(val body: ResponseBody)

fun interface StorytellerBundleApi {
    suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream>
}

fun interface StorytellerBundleProbeApi {
    suspend fun probeBundleSize(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long>
}

class StorytellerBundleApiImpl(
    private val client: OkHttpClient,
    // Overridable so tests can assert the bounded-timeout fallback without waiting the full window.
    private val sidecarCallTimeoutSeconds: Long = SIDECAR_CALL_TIMEOUT_SECONDS,
    private val sidecarStreamTimeoutSeconds: Long = SIDECAR_STREAM_TIMEOUT_SECONDS,
) : StorytellerBundleApi, StorytellerBundleProbeApi {

    private val bundleClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sidecarClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(sidecarCallTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val sidecarStreamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(sidecarStreamTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    /**
     * Streaming GET of `/synced` for sidecar extraction (ADR 0028) — same as [downloadBundle] but on a
     * BOUNDED client so a wedged generation fails instead of hanging the background prepare forever.
     */
    suspend fun streamSidecar(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream> = openStream(sidecarStreamClient, baseUrl, bookId, token, insecureAllowed)

    override suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream> = openStream(bundleClient, baseUrl, bookId, token, insecureAllowed)

    private suspend fun openStream(
        chosenClient: OkHttpClient,
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream> = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) chosenClient.trustAllCerts() else chosenClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .addHeader("Authorization", "Bearer $token")
            .build()
        try {
            val response = effectiveClient.newCall(request).execute()
            val body = response.body ?: return@withContext NetworkResult.Offline(IOException("Empty response body"))
            if (response.isSuccessful) {
                // execute() blocks through Storyteller's slow /synced header wait; if the coroutine was
                // cancelled, close the body ourselves before withContext discards the (otherwise leaked)
                // Success.
                try {
                    ensureActive()
                } catch (e: Throwable) {
                    body.close()
                    throw e
                }
                NetworkResult.Success(StorytellerBundleStream(body))
            } else {
                body.close()
                NetworkResult.Offline(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }

    override suspend fun probeBundleSize(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long> = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) sidecarClient.trustAllCerts() else sidecarClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .head()
            .addHeader("Authorization", "Bearer $token")
            .build()
        try {
            effectiveClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext NetworkResult.Offline(IOException("HTTP ${response.code}"))
                }
                val length = response.header("Content-Length")?.toLongOrNull()
                    ?: return@withContext NetworkResult.Offline(IOException("Missing Content-Length"))
                NetworkResult.Success(length)
            }
        } catch (e: IOException) {
            NetworkResult.Offline(e)
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

    private companion object {
        const val SIDECAR_CALL_TIMEOUT_SECONDS = 15L
        const val SIDECAR_STREAM_TIMEOUT_SECONDS = 240L
    }
}
