package com.riffle.core.data

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

    override suspend fun list(serverId: String, itemId: String): List<String> =
        withContext(Dispatchers.IO) {
            val url = bookUrl(serverId, itemId)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> emptyList()
                    response.code == 401 || response.code == 403 ->
                        throw AnnotationSyncException.AuthFailed(response.code)
                    !response.isSuccessful && response.code != 207 ->
                        throw AnnotationSyncException.HttpFailure(response.code, "list")
                    else -> {
                        val body = response.body.string()
                        parsePropfindFilenames(body)
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
            val url = fileUrl(serverId, itemId, filename)
            val body = content.toRequestBody(JSON_LD_MEDIA)

            val first = put(url, body)
            if (first.isSuccessful) {
                first.close()
                return@withContext
            }
            val code = first.code
            first.close()
            when (code) {
                401, 403 -> throw AnnotationSyncException.AuthFailed(code)
                409, 404 -> {
                    ensureParentCollections(serverId, itemId)
                    val retry = put(url, body)
                    val retryCode = retry.code
                    val retryOk = retry.isSuccessful
                    retry.close()
                    if (!retryOk) {
                        throw AnnotationSyncException.HttpFailure(retryCode, "write $filename")
                    }
                }
                else -> throw AnnotationSyncException.HttpFailure(code, "write $filename")
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

    private fun ensureParentCollections(serverId: String, itemId: String) {
        // Walk down from base, MKCOLing each segment we own. 405 = already exists, fine.
        val parents = listOf(basePath, basePath.newBuilder().addPathSegment(serverId).build())
        val terminal = parents.last().newBuilder().addPathSegment(itemId).build()
        val toCreate = listOf(basePath, parents[1], terminal)
        for (dir in toCreate) {
            val request = Request.Builder()
                .url(ensureTrailingSlash(dir))
                .header("Authorization", authHeader)
                .method("MKCOL", null)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 405) {
                    when (resp.code) {
                        401, 403 -> throw AnnotationSyncException.AuthFailed(resp.code)
                        else -> throw AnnotationSyncException.HttpFailure(resp.code, "mkcol")
                    }
                }
            }
        }
    }

    private fun put(url: HttpUrl, body: RequestBody): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .put(body)
            .build()
        return client.newCall(request).execute()
    }

    private fun bookUrl(serverId: String, itemId: String): HttpUrl =
        ensureTrailingSlash(
            basePath.newBuilder()
                .addPathSegment(serverId)
                .addPathSegment(itemId)
                .build(),
        )

    private fun fileUrl(serverId: String, itemId: String, filename: String): HttpUrl =
        basePath.newBuilder()
            .addPathSegment(serverId)
            .addPathSegment(itemId)
            .addPathSegment(filename)
            .build()

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
