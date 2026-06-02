package com.riffle.core.network

import kotlinx.coroutines.Dispatchers
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

sealed interface NetworkAudiobookBundleResult {
    /**
     * @param body the (possibly partial) stream of bundle bytes; caller must close it.
     * @param totalBytes the full bundle size, recovered from Content-Range (206) or Content-Length (200).
     * @param isPartial true when the server honoured a Range request (206); false on a full 200 body.
     */
    data class Success(val body: ResponseBody, val totalBytes: Long, val isPartial: Boolean) : NetworkAudiobookBundleResult
    data class NetworkError(val cause: Throwable) : NetworkAudiobookBundleResult
}

/**
 * Opens a (resumable) byte stream of the Storyteller synced bundle — the EPUB-3-with-audio that
 * is the Readaloud audio source (see ADR 0023). [fromByte] > 0 issues a `Range` request so an
 * interrupted download can pick up where it left off.
 */
fun interface AudiobookBundleApi {
    suspend fun openBundleStream(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        fromByte: Long,
    ): NetworkAudiobookBundleResult
}

class AudiobookBundleApiImpl(
    private val client: OkHttpClient,
) : AudiobookBundleApi {

    // Bundles run to hundreds of MB; disable the idle-read timeout (the connect/call paths still
    // apply) so a slow multi-minute stream isn't killed mid-flight.
    private val bundleClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun openBundleStream(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        fromByte: Long,
    ): NetworkAudiobookBundleResult = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) bundleClient.trustAllCerts() else bundleClient
        val builder = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .addHeader("Authorization", "Bearer $token")
            // Forward-compat: a Storyteller release that content-negotiates a Readium audiobook
            // archive will honour this; today's server ignores it and returns application/epub+zip.
            .addHeader("Accept", "application/audiobook+zip")
        if (fromByte > 0L) builder.addHeader("Range", "bytes=$fromByte-")

        try {
            val response = effectiveClient.newCall(builder.build()).execute()
            val body = response.body
            if (!response.isSuccessful || body == null) {
                body?.close()
                return@withContext NetworkAudiobookBundleResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            val isPartial = response.code == 206
            val total = if (isPartial) {
                response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            } else {
                response.header("Content-Length")?.toLongOrNull()
            } ?: -1L
            NetworkAudiobookBundleResult.Success(body = body, totalBytes = total, isPartial = isPartial)
        } catch (e: IOException) {
            NetworkAudiobookBundleResult.NetworkError(e)
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
        return newBuilder().sslSocketFactory(sslContext.socketFactory, trustAll).build()
    }
}
