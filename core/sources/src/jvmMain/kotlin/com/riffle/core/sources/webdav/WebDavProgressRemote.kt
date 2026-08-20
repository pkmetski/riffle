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
 * Implements the ebook and audiobook position side of ADR 0063 — one file per book, no per-device split.
 *
 * File URL: `{basePath}{namespace}__{itemId}__progress.json`
 * GET: parse [ProgressPayload]; use `Last-Modified` response header as [RemoteProgress.lastUpdate].
 * PUT: write [ProgressPayload]; adopt `Last-Modified` from response as the server stamp.
 *
 * HTTP 404 on GET returns `lastUpdate=0` rather than null — the Offline sentinel — so a dirty
 * local row (first sync from this device) fires LocalWins and creates the file. All other HTTP
 * and network errors return null so the sweep retries next cycle.
 *
 * For audiobook progress (position in seconds as [Double]), use [asAudioRemote] to get a
 * [ProgressRemote]<Double> backed by the same file. The seconds value is stored as a decimal
 * string in [ProgressPayload.position] and parsed back on read.
 */
class WebDavProgressRemote(
    private val client: HttpClient,
    private val authHeader: String,
    private val progressFileUrl: String,
    private val readingProgress: suspend () -> Float,
    private val finishedAt: suspend () -> Long?,
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
                // File doesn't exist yet — not Offline; return lastUpdate=0 so LocalWins fires
                // on the first sweep and creates the file.
                404 -> RemoteProgress(position = "", lastUpdate = 0L, readingProgress = 0f, finishedAt = null)
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
            val payload = ProgressPayload(
                position = position,
                readingProgress = readingProgress(),
                finishedAt = finishedAt(),
                lastUpdate = clock.nowMs(),
            )
            val response = client.put(progressFileUrl) {
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                setBody(TextContent(json.encodeToString(ProgressPayload.serializer(), payload), ContentType.parse(JSON_CONTENT_TYPE)))
            }
            // Capture the clock AFTER the PUT so the fallback stamp is no older than the server
            // write. Synology WebDAV does not return Last-Modified on PUT responses.
            val fallback = clock.nowMs()
            when (response.status.value) {
                in 200..299 -> response.headers[HttpHeaders.LastModified]?.let { parseHttpDate(it) } ?: fallback
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Returns a [ProgressRemote]<Double> backed by this same WebDAV file. The Double position
     * (audiobook seconds) is stored as a decimal string in [ProgressPayload.position].
     */
    fun asAudioRemote(): ProgressRemote<Double> = object : ProgressRemote<Double> {
        override suspend fun get(): RemoteProgress<Double>? {
            val r = this@WebDavProgressRemote.get() ?: return null
            return RemoteProgress(
                position = r.position.toDoubleOrNull() ?: 0.0,
                lastUpdate = r.lastUpdate,
                readingProgress = r.readingProgress,
                finishedAt = r.finishedAt,
            )
        }

        override suspend fun patch(position: Double): Long? =
            this@WebDavProgressRemote.patch(position.toString())
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
        const val NAMESPACE_SEPARATOR = "__"
        const val EBOOK_PROGRESS_SUFFIX = "${NAMESPACE_SEPARATOR}progress.json"
        // Distinct suffix so WebDavProgressEnumerator can categorise files from PROPFIND without
        // ambiguity — both types would otherwise end with "__progress.json".
        const val AUDIO_PROGRESS_SUFFIX = "${NAMESPACE_SEPARATOR}audio_progress.json"

        internal val json = Json { ignoreUnknownKeys = true }

        internal fun parseHttpDate(value: String): Long? = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
        }.getOrNull()

        fun progressFileUrl(
            basePath: String,
            namespace: String,
            itemId: String,
            suffix: String = EBOOK_PROGRESS_SUFFIX,
        ): String {
            val base = if (basePath.endsWith("/")) basePath else "$basePath/"
            // Replace '/' with '.' so Synology and other WebDAV servers that decode %2F as a path
            // separator don't split the filename into a nonexistent subdirectory. Chitanka itemIds
            // look like "book/12073-xxx"; Gutenberg IDs contain no '/' or '.', so no collision risk.
            val safeNamespace = namespace.replace('/', '.')
            val safeItemId = itemId.replace('/', '.')
            return "$base$safeNamespace$NAMESPACE_SEPARATOR$safeItemId$suffix"
        }
    }
}
