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

sealed interface NetworkStorytellerBundleResult {
    data class Success(val body: ResponseBody) : NetworkStorytellerBundleResult
    data class NetworkError(val cause: Throwable) : NetworkStorytellerBundleResult
}

sealed interface NetworkStorytellerBundleSizeResult {
    data class Success(val sizeBytes: Long) : NetworkStorytellerBundleSizeResult
    data class NetworkError(val cause: Throwable) : NetworkStorytellerBundleSizeResult
}

fun interface StorytellerBundleApi {
    suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleResult
}

fun interface StorytellerBundleProbeApi {
    suspend fun probeBundleSize(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleSizeResult
}

sealed interface NetworkRangeResult {
    data class Success(val bytes: ByteArray) : NetworkRangeResult
    data class NetworkError(val cause: Throwable) : NetworkRangeResult
}

/** Reads a byte range of the `/synced` bundle — the transport behind sidecar extraction (ADR 0028). */
fun interface StorytellerRangeApi {
    suspend fun readBundleRange(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        offset: Long,
        length: Int,
    ): NetworkRangeResult
}

class StorytellerBundleApiImpl(
    private val client: OkHttpClient,
) : StorytellerBundleApi, StorytellerBundleProbeApi, StorytellerRangeApi {

    // Bundles can be hundreds of MB; the default 10s read timeout times out mid-stream
    // on anything but the smallest aligned EPUBs. readTimeout(0) = no idle-read timeout
    // (the connection itself still has connect/call timeouts).
    //
    // Storyteller also takes 1.5–5 s to answer HEAD on /synced (it appears to compute
    // the resource lazily), so the same extended client is used for the probe — otherwise
    // probing larger or cold books trips the default 10 s read timeout before the size
    // gate has a chance to refuse the download.
    private val bundleClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleResult = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) bundleClient.trustAllCerts() else bundleClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .addHeader("Authorization", "Bearer $token")
            .build()
        try {
            val response = effectiveClient.newCall(request).execute()
            val body = response.body ?: return@withContext NetworkStorytellerBundleResult.NetworkError(
                IOException("Empty response body")
            )
            if (response.isSuccessful) {
                // execute() blocks through Storyteller's slow /synced header wait; if the coroutine
                // was cancelled during it, withContext will discard this Success and leak the open
                // body. Close it ourselves before handing ownership to the (live) caller.
                try {
                    ensureActive()
                } catch (e: Throwable) {
                    body.close()
                    throw e
                }
                NetworkStorytellerBundleResult.Success(body)
            } else {
                body.close()
                NetworkStorytellerBundleResult.NetworkError(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkStorytellerBundleResult.NetworkError(e)
        }
    }

    override suspend fun probeBundleSize(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBundleSizeResult = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) bundleClient.trustAllCerts() else bundleClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .head()
            .addHeader("Authorization", "Bearer $token")
            .build()
        try {
            effectiveClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext NetworkStorytellerBundleSizeResult.NetworkError(
                        IOException("HTTP ${response.code}")
                    )
                }
                val length = response.header("Content-Length")?.toLongOrNull()
                    ?: return@withContext NetworkStorytellerBundleSizeResult.NetworkError(
                        IOException("Missing Content-Length")
                    )
                NetworkStorytellerBundleSizeResult.Success(length)
            }
        } catch (e: IOException) {
            NetworkStorytellerBundleSizeResult.NetworkError(e)
        }
    }

    override suspend fun readBundleRange(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        offset: Long,
        length: Int,
    ): NetworkRangeResult = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) bundleClient.trustAllCerts() else bundleClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        try {
            effectiveClient.newCall(request).execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    return@withContext NetworkRangeResult.NetworkError(IOException("HTTP ${response.code}"))
                }
                val bytes = response.body?.bytes()
                    ?: return@withContext NetworkRangeResult.NetworkError(IOException("Empty range body"))
                NetworkRangeResult.Success(bytes)
            }
        } catch (e: IOException) {
            NetworkRangeResult.NetworkError(e)
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
