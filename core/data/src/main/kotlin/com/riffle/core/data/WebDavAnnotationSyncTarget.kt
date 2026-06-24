package com.riffle.core.data

import android.util.Log
import com.riffle.core.domain.AnnotationSyncTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/**
 * WebDAV-backed [AnnotationSyncTarget].
 *
 * Layout: `<baseUrl>/<serverId>/<itemId>/<filename>`. The class speaks `PROPFIND`,
 * `GET`, `PUT` and `MKCOL` against a user-supplied WebDAV server (Nextcloud,
 * ownCloud, Apache `mod_dav`, Synology, etc.), authenticating with HTTP Basic.
 */
class WebDavAnnotationSyncTarget(
    baseUrl: HttpUrl,
    username: String,
    password: String,
    private val client: OkHttpClient,
) : AnnotationSyncTarget {

    private val basePath: HttpUrl = ensureTrailingSlash(baseUrl)
    private val authHeader: String = Credentials.basic(username, password)

    init {
        Log.d(TAG, "WebDavAnnotationSyncTarget initialised — basePath=$basePath user=$username")
    }

    override suspend fun list(serverId: String, itemId: String): List<String> =
        withContext(Dispatchers.IO) {
            // Flat-layout per-device files live directly under basePath: PROPFIND the base
            // directory, then filter to entries whose physical name carries the matching
            // <serverId>__<itemId>__ prefix and return the logical (post-prefix) filename.
            val request = Request.Builder()
                .url(basePath)
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    // Most servers answer "PROPFIND on a non-existent collection" with 404; Synology
                    // (and a few others) answer 405 instead. Treat both as "nothing there yet" so the
                    // sync can proceed to create the directory on first push.
                    response.code == 404 || response.code == 405 -> emptyList()
                    response.code == 401 || response.code == 403 ->
                        throw AnnotationSyncException.AuthFailed(response.code)
                    !response.isSuccessful && response.code != 207 ->
                        throw AnnotationSyncException.HttpFailure(response.code, "list")
                    else -> {
                        val body = response.body.string()
                        val prefix = physicalPrefix(serverId, itemId)
                        parsePropfindFilenames(body)
                            .filter { it.startsWith(prefix) }
                            .map { it.removePrefix(prefix) }
                    }
                }
            }
        }

    override suspend fun read(serverId: String, itemId: String, filename: String): String? =
        withContext(Dispatchers.IO) {
            val url = fileUrl(serverId, itemId, filename)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    response.code == 401 || response.code == 403 ->
                        throw AnnotationSyncException.AuthFailed(response.code)
                    !response.isSuccessful ->
                        throw AnnotationSyncException.HttpFailure(response.code, "read $filename")
                    else -> response.body.string()
                }
            }
        }

    override suspend fun write(
        serverId: String,
        itemId: String,
        filename: String,
        content: String,
    ) {
        withContext(Dispatchers.IO) {
            // Flat path under basePath. No per-book subdirectories means the only collection
            // that needs to exist is basePath itself, which the user has already vouched for via
            // Test Connection (Synology's WebDAV refuses MKCOL on share-subfolders, so a flat
            // layout sidesteps that without losing per-device-file semantics — the
            // `<serverId>__<itemId>__` filename prefix keeps the namespacing intact).
            val url = fileUrl(serverId, itemId, filename)
            val body = content.toRequestBody(JSON_LD_MEDIA)
            val response = put(url, body)
            val code = response.code
            val ok = response.isSuccessful
            response.close()
            if (!ok) {
                when (code) {
                    401, 403 -> throw AnnotationSyncException.AuthFailed(code)
                    else -> throw AnnotationSyncException.HttpFailure(code, "write $filename")
                }
            }
        }
    }

    suspend fun testConnection(): TestConnectionResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(basePath)
            .header("Authorization", authHeader)
            .header("Depth", "0")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 207 -> TestConnectionResult.Success
                    response.code == 401 || response.code == 403 -> TestConnectionResult.AuthFailed
                    response.code == 404 -> tryCreateBase()
                    response.code in 500..599 -> TestConnectionResult.ServerError(response.code)
                    else -> TestConnectionResult.ServerError(response.code)
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            TestConnectionResult.TlsError(e.message ?: "TLS error")
        } catch (e: java.io.IOException) {
            TestConnectionResult.NetworkError(e.message ?: "Network error")
        }
    }

    private fun tryCreateBase(): TestConnectionResult = try {
        val mkcol = Request.Builder()
            .url(basePath)
            .header("Authorization", authHeader)
            .method("MKCOL", null)
            .build()
        client.newCall(mkcol).execute().use { resp ->
            when {
                resp.isSuccessful || resp.code == 405 -> TestConnectionResult.Success
                resp.code == 401 || resp.code == 403 -> TestConnectionResult.AuthFailed
                else -> TestConnectionResult.ServerError(resp.code)
            }
        }
    } catch (e: javax.net.ssl.SSLException) {
        TestConnectionResult.TlsError(e.message ?: "TLS error")
    } catch (e: java.io.IOException) {
        TestConnectionResult.NetworkError(e.message ?: "Network error")
    }

    private fun put(url: HttpUrl, body: RequestBody): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .put(body)
            .build()
        val response = client.newCall(request).execute()
        Log.d(TAG, "PUT $url -> ${response.code}")
        return response
    }

    private fun fileUrl(serverId: String, itemId: String, filename: String): HttpUrl =
        basePath.newBuilder()
            .addPathSegment(physicalPrefix(serverId, itemId) + filename)
            .build()

    /** Composite filename prefix that emulates the per-book directory: `<serverId>__<itemId>__`. */
    private fun physicalPrefix(serverId: String, itemId: String): String =
        "$serverId$NAMESPACE_SEPARATOR$itemId$NAMESPACE_SEPARATOR"

    private fun ensureTrailingSlash(url: HttpUrl): HttpUrl {
        val segs = url.pathSegments
        return if (segs.isNotEmpty() && segs.last().isEmpty()) url
        else url.newBuilder().addPathSegment("").build()
    }

    private fun parsePropfindFilenames(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val handler = HrefCollector()
        try {
            val parser = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
            }.newSAXParser()
            parser.parse(xml.byteInputStream(Charsets.UTF_8), handler)
        } catch (_: Exception) {
            return emptyList()
        }
        return handler.hrefs
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() && it.endsWith(".jsonld") }
    }

    private class HrefCollector : DefaultHandler() {
        val hrefs = mutableListOf<String>()
        private val current = StringBuilder()
        private var inHref = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (localName == "href") {
                inHref = true
                current.setLength(0)
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inHref && ch != null) current.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (localName == "href") {
                hrefs.add(current.toString().trim())
                inHref = false
            }
        }
    }

    companion object {
        private const val TAG = "RIFFLE_ANNO_SYNC"
        private const val NAMESPACE_SEPARATOR = "__"
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private val JSON_LD_MEDIA = "application/ld+json; charset=utf-8".toMediaType()

        // Minimal PROPFIND asking for resourcetype on each child resource.
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
    }
}

/** Result of [WebDavAnnotationSyncTarget.testConnection]. */
sealed class TestConnectionResult {
    object Success : TestConnectionResult()
    object AuthFailed : TestConnectionResult()
    data class TlsError(val message: String) : TestConnectionResult()
    data class NetworkError(val message: String) : TestConnectionResult()
    data class ServerError(val code: Int) : TestConnectionResult()
    data class InvalidUrl(val message: String) : TestConnectionResult()
}

/** Distinct failure modes thrown out of the data-path methods (list/read/write). */
sealed class AnnotationSyncException(message: String) : Exception(message) {
    class AuthFailed(val code: Int) : AnnotationSyncException("WebDAV authentication failed ($code)")
    class HttpFailure(val code: Int, val operation: String) :
        AnnotationSyncException("WebDAV $operation failed with HTTP $code")
}

/** Parse a user-supplied URL; returns null on malformed input. */
internal fun parseWebDavBaseUrl(raw: String): HttpUrl? =
    raw.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
