package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.withContext
import java.util.Base64

/** Ebook and audio item IDs found on the WebDAV share by PROPFIND. */
data class EnumeratedProgress(
    /** safe itemIds (slash replaced with dot) for ebook progress files. */
    val ebookSafeIds: List<String>,
    /** safe itemIds (slash replaced with dot) for audio progress files. */
    val audioSafeIds: List<String>,
) {
    companion object {
        val EMPTY = EnumeratedProgress(emptyList(), emptyList())
    }
}

/**
 * Enumerates progress files on a WebDAV share for a given namespace via PROPFIND.
 *
 * Ebook and audio files are distinguished by suffix:
 *   ebook → [WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX]
 *   audio → [WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX]
 *
 * Returned item IDs are in "safe" form (itemId with '/' replaced by '.'), as stored in the
 * filename. Callers are responsible for reversing the encoding where needed (e.g. by joining
 * against the local DB — see CatalogRemoteProgressIndex).
 */
open class WebDavProgressEnumerator(
    httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val httpClient = httpClient.config {
        install(HttpTimeout) {
            requestTimeoutMillis = PROPFIND_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = READ_TIMEOUT_MS
        }
    }

    open suspend fun enumerate(config: AnnotationSyncConfig, namespace: String): EnumeratedProgress =
        withContext(dispatchers.io) {
            val baseUrl = parseWebDavBaseUrl(config.baseUrl) ?: return@withContext EnumeratedProgress.EMPTY
            val basePath = baseUrl.toString().let { if (it.endsWith("/")) it else "$it/" }
            val authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("${config.username}:${config.password}".toByteArray())

            val filenames = runCatching {
                classifyWebDavTransportErrors {
                    val response = httpClient.request(basePath) {
                        method = HttpMethod("PROPFIND")
                        header(HttpHeaders.Authorization, authHeader)
                        header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                        header("Depth", "1")
                        setBody(TextContent(PROPFIND_BODY, ContentType.parse(XML_CONTENT_TYPE)))
                    }
                    when (response.status.value) {
                        400, 404, 405 -> emptyList()
                        401, 403 -> throw AnnotationSyncException.AuthFailed(response.status.value)
                        207, in 200..299 -> parsePropfindFilenames(response.bodyAsText())
                        else -> emptyList()
                    }
                }
            }.getOrDefault(emptyList())

            val nsPrefix = "${namespace.replace('/', '.')}${WebDavProgressRemote.NAMESPACE_SEPARATOR}"
            val ebookIds = mutableListOf<String>()
            val audioIds = mutableListOf<String>()
            for (filename in filenames) {
                if (!filename.startsWith(nsPrefix)) continue
                val afterNs = filename.removePrefix(nsPrefix)
                when {
                    afterNs.endsWith(WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX) ->
                        afterNs.removeSuffix(WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX)
                            .takeIf { it.isNotEmpty() }?.let { audioIds.add(it) }
                    afterNs.endsWith(WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX) ->
                        afterNs.removeSuffix(WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX)
                            .takeIf { it.isNotEmpty() }?.let { ebookIds.add(it) }
                }
            }
            EnumeratedProgress(ebookIds, audioIds)
        }

    companion object {
        private const val FINDER_USER_AGENT = "WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)"
        private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        private const val PROPFIND_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 20_000L
    }
}
