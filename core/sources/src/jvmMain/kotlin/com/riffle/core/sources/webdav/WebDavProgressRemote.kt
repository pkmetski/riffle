package com.riffle.core.sources.webdav

import com.riffle.core.common.Clock
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * [ProgressRemote] that reads and writes a single canonical progress file on a WebDAV server.
 * Implements the ebook position side of ADR 0062 — one file per book, no per-device split.
 *
 * File URL: `{basePath}{namespace}__{itemId}__progress.json`
 * GET: parse [ProgressPayload]; use `Last-Modified` response header as [RemoteProgress.lastUpdate].
 * PUT: write [ProgressPayload]; adopt `Last-Modified` from response as the server stamp.
 *
 * All HTTP and network errors return null — the caller's sweep retries the next cycle.
 */
class WebDavProgressRemote(
    private val client: HttpClient,
    private val authHeader: String,
    private val progressFileUrl: String,
    private val readingProgress: suspend () -> Float,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : ProgressRemote<String> {

    override suspend fun get(): RemoteProgress<String>? = withContext(dispatchers.io) {
        runCatching {
            val response = client.get(progressFileUrl) {
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
            }
            when (response.status.value) {
                404 -> null
                in 200..299 -> {
                    val payload = json.decodeFromString<ProgressPayload>(response.bodyAsText())
                    val lastModified = response.headers[HttpHeaders.LastModified]
                        ?.let { parseHttpDate(it) }
                        ?: payload.lastUpdate
                    RemoteProgress(
                        position = payload.position,
                        lastUpdate = lastModified,
                        readingProgress = payload.readingProgress,
                        finishedAt = payload.finishedAt,
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    override suspend fun patch(position: String): Long? = withContext(dispatchers.io) {
        runCatching {
            val now = clock.nowMs()
            val payload = ProgressPayload(
                position = position,
                readingProgress = readingProgress(),
                finishedAt = null,
                lastUpdate = now,
            )
            val response = client.put(progressFileUrl) {
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                setBody(TextContent(json.encodeToString(ProgressPayload.serializer(), payload), ContentType.parse(JSON_CONTENT_TYPE)))
            }
            when (response.status.value) {
                in 200..299 -> response.headers[HttpHeaders.LastModified]?.let { parseHttpDate(it) } ?: now
                else -> null
            }
        }.getOrNull()
    }

    @Serializable
    data class ProgressPayload(
        val position: String,
        val readingProgress: Float,
        val finishedAt: Long? = null,
        val lastUpdate: Long,
    )

    companion object {
        private const val FINDER_USER_AGENT = "WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val NAMESPACE_SEPARATOR = "__"
        private const val PROGRESS_SUFFIX = "${NAMESPACE_SEPARATOR}progress.json"

        internal val json = Json { ignoreUnknownKeys = true }

        internal fun parseHttpDate(value: String): Long? = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
        }.getOrNull()

        fun progressFileUrl(basePath: String, namespace: String, itemId: String): String {
            val base = if (basePath.endsWith("/")) basePath else "$basePath/"
            return "$base$namespace$NAMESPACE_SEPARATOR$itemId$PROGRESS_SUFFIX"
        }
    }
}
