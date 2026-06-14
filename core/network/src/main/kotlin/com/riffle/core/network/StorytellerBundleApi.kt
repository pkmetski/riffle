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

class StorytellerBundleApiImpl(
    private val client: OkHttpClient,
    // Overridable so tests can assert the bounded-timeout fallback without waiting the full window.
    private val sidecarCallTimeoutSeconds: Long = SIDECAR_CALL_TIMEOUT_SECONDS,
    private val sidecarStreamTimeoutSeconds: Long = SIDECAR_STREAM_TIMEOUT_SECONDS,
) : StorytellerBundleApi, StorytellerBundleProbeApi {

    // Bundles can be hundreds of MB; the default 10s read timeout times out mid-stream
    // on anything but the smallest aligned EPUBs. readTimeout(0) = no idle-read timeout
    // (the connection itself still has connect/call timeouts). Used only for the explicit,
    // progress-tracked full download — where an unbounded wait is what the user opted into.
    private val bundleClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // The size probe (HEAD) and the small sidecar range reads must NOT inherit the download's
    // unbounded timeout. Storyteller serves /synced by lazily generating the whole aligned
    // bundle before the first byte — for a large cold book that can be minutes (observed >90s),
    // not the documented 1.5–5s. On the streaming-play path the sidecar fetch is awaited behind
    // the Play tap, so an unbounded probe wedges playback forever (ADR 0028). A bounded callTimeout
    // makes a cold /synced fail fast: the streaming build returns null and falls back, and the
    // download size gate degrades to its existing 0-byte prompt rather than hanging.
    private val sidecarClient: OkHttpClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(sidecarCallTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    // The streaming sidecar fetch ([streamSidecar]) reads only the ~1 MB non-audio prefix and closes
    // early, but Storyteller still takes up to ~90s to emit the first byte (whole-bundle generation).
    // It runs in the background behind a "Preparing…" indicator, so it tolerates that wait — but it must
    // still be BOUNDED: a coroutine timeout can't cancel the blocking execute(), so without a real
    // callTimeout a wedged /synced leaves the prepare (and the indicator) stuck forever. readTimeout(0)
    // because the first byte legitimately lags; callTimeout caps the whole call so a dead /synced fails.
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
    ): NetworkStorytellerBundleResult = withContext(Dispatchers.IO) {
        val effectiveClient = if (insecureAllowed) sidecarStreamClient.trustAllCerts() else sidecarStreamClient
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId/synced")
            .addHeader("Authorization", "Bearer $token")
            .build()
        try {
            val response = effectiveClient.newCall(request).execute()
            val body = response.body ?: return@withContext NetworkStorytellerBundleResult.NetworkError(IOException("Empty response body"))
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
        val effectiveClient = if (insecureAllowed) sidecarClient.trustAllCerts() else sidecarClient
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
        // Generous enough for a slow-but-working /synced (HEAD documented at 1.5–5s; range reads
        // are small), tight enough that a cold/hung /synced fails fast instead of wedging playback.
        const val SIDECAR_CALL_TIMEOUT_SECONDS = 15L

        // The streaming prefix fetch reads only ~1 MB but must wait out the whole-bundle generation first
        // (the server emits no byte until it's built the bundle — observed up to ~120s). Generous so a
        // slow-but-working /synced (the same one a full download tolerates) still succeeds, yet bounded so
        // a wedged /synced can't leave the "Preparing…" indicator stuck forever.
        const val SIDECAR_STREAM_TIMEOUT_SECONDS = 240L
    }
}
