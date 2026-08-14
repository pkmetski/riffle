package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream

/** A streaming Storyteller bundle response — caller must close [body]. */
data class StorytellerBundleStream(val body: InputStream)

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
    private val client: HttpClient,
    // Overridable so tests can assert the bounded-timeout fallback without waiting the full window.
    private val sidecarCallTimeoutSeconds: Long = SIDECAR_CALL_TIMEOUT_SECONDS,
    private val sidecarStreamTimeoutSeconds: Long = SIDECAR_STREAM_TIMEOUT_SECONDS,
) : StorytellerBundleApi, StorytellerBundleProbeApi {

    /**
     * Streaming GET of `/synced` for sidecar extraction (ADR 0040) — same as [downloadBundle] but on a
     * BOUNDED timeout so a wedged generation fails instead of hanging the background prepare forever.
     */
    suspend fun streamSidecar(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream> =
        openStream(sidecarStreamTimeoutSeconds * 1_000L, baseUrl, bookId, token, insecureAllowed)

    override suspend fun downloadBundle(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream> =
        openStream(Long.MAX_VALUE, baseUrl, bookId, token, insecureAllowed)

    private suspend fun openStream(
        timeoutMs: Long,
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerBundleStream> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return try {
            val response = effectiveClient.get("$baseUrl/api/books/$bookId/synced") {
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout {
                    connectTimeoutMillis = 30_000L
                    requestTimeoutMillis = timeoutMs
                    socketTimeoutMillis = if (timeoutMs == Long.MAX_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        timeoutMs
                    }
                }
            }
            val channel = response.bodyAsChannel()
            if (!response.status.isSuccess()) {
                channel.cancel(IOException("HTTP ${response.status.value}"))
                return NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
            }
            // get() suspends through Storyteller's slow /synced header wait; if the coroutine was
            // cancelled, cancel the channel before the scope discards the (otherwise leaked) Success.
            currentCoroutineContext().ensureActive()
            NetworkResult.Success(StorytellerBundleStream(body = channel.toInputStream()))
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }

    override suspend fun probeBundleSize(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return try {
            val response = effectiveClient.head("$baseUrl/api/books/$bookId/synced") {
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout {
                    requestTimeoutMillis = sidecarCallTimeoutSeconds * 1_000L
                    socketTimeoutMillis = sidecarCallTimeoutSeconds * 1_000L
                }
            }
            if (!response.status.isSuccess()) {
                return NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
            }
            val length = response.contentLength()
                ?: return NetworkResult.Offline(IOException("Missing Content-Length"))
            NetworkResult.Success(length)
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }

    private companion object {
        const val SIDECAR_CALL_TIMEOUT_SECONDS = 15L
        const val SIDECAR_STREAM_TIMEOUT_SECONDS = 240L
    }
}
