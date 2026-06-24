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
 * **Layout — flat per-device files keyed by composite filename.** Every file lives directly
 * under `basePath` and carries the per-account+book scope in its name:
 * ```
 * <basePath>/<namespace>__<itemId>__<filename>
 * ```
 * `namespace` is the cross-device-stable ABS user id (`/api/me` → `user.id`, persisted on
 * [com.riffle.core.domain.Server.absUserId]). Using the local `servers.id` here would break
 * cross-device sync — see [com.riffle.core.domain.AnnotationSyncTarget] kdoc for the full
 * rationale.
 * We *don't* nest the `<serverId>` / `<itemId>` segments as subdirectories: Synology DSM's
 * WebDAV server refuses MKCOL on shared-folder subpaths in ways we couldn't get around even
 * with the Finder UA (PROPFIND and MKCOL both return 400 for bare-UUID directory names that
 * the server has already seen and discarded). Keeping the layout flat means the only
 * collection that has to exist is `basePath` itself, which the user vouches for via Test
 * Connection. Every other standard WebDAV server (Nextcloud, ownCloud, Apache `mod_dav`,
 * etc.) accepts flat names too, so this layout doesn't regress anything.
 *
 * Auth: HTTP basic. Every request is also tagged with the macOS Finder WebDAVFS
 * User-Agent — Synology in particular gates write methods on a UA allow-list and rejects
 * the OkHttp default with 424.
 */
class WebDavAnnotationSyncTarget(
    baseUrl: HttpUrl,
    username: String,
    password: String,
    private val client: OkHttpClient,
) : AnnotationSyncTarget {

    private val basePath: HttpUrl = ensureTrailingSlash(baseUrl)
    private val authHeader: String = Credentials.basic(username, password)

    override suspend fun list(namespace: String, itemId: String): List<String> =
        withContext(Dispatchers.IO) {
            // PROPFIND basePath, then filter to entries whose physical name carries the matching
            // `<namespace>__<itemId>__` prefix; return the logical (post-prefix) filename so the
            // controller sees the unprefixed name unchanged.
            val request = baseRequest(basePath)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code in setOf(400, 404, 405) -> emptyList()
                    response.code == 401 || response.code == 403 ->
                        throw AnnotationSyncException.AuthFailed(response.code)
                    !response.isSuccessful && response.code != 207 ->
                        throw AnnotationSyncException.HttpFailure(response.code, "list")
                    else -> {
                        val body = response.body.string()
                        val prefix = physicalPrefix(namespace, itemId)
                        parsePropfindFilenames(body)
                            .filter { it.startsWith(prefix) }
                            .map { it.removePrefix(prefix) }
                    }
                }
            }
        }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? =
        withContext(Dispatchers.IO) {
            val url = fileUrl(namespace, itemId, filename)
            val request = baseRequest(url).get().build()
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
        namespace: String,
        itemId: String,
        filename: String,
        content: String,
    ) {
        withContext(Dispatchers.IO) {
            // Flat path under basePath. No per-book subdirectories means MKCOL never has to fire on
            // a real push — the only collection that must exist is basePath itself, which the user
            // has already vouched for via Test Connection (and which we MKCOL-create on demand).
            val url = fileUrl(namespace, itemId, filename)
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
        val request = baseRequest(basePath)
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
        val mkcol = baseRequest(basePath)
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
        val request = baseRequest(url).put(body).build()
        return client.newCall(request).execute()
    }

    /**
     * Every WebDAV request built here is auth'd and tagged with a Finder User-Agent. Some servers —
     * Synology DSM's WebDAV Server in particular — return 424 for MKCOL when the UA looks
     * unfamiliar, even when the user holds Read/Write on the share. Spoofing macOS Finder's
     * WebDAVFS UA takes us out of that lane (verified against pkmetski.synology.me).
     */
    private fun baseRequest(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", authHeader)
        .header("User-Agent", FINDER_USER_AGENT)

    private fun fileUrl(namespace: String, itemId: String, filename: String): HttpUrl =
        basePath.newBuilder()
            .addPathSegment(physicalPrefix(namespace, itemId) + filename)
            .build()

    /** Composite filename prefix that emulates the per-book directory: `<namespace>__<itemId>__`. */
    private fun physicalPrefix(namespace: String, itemId: String): String =
        "$namespace$NAMESPACE_SEPARATOR$itemId$NAMESPACE_SEPARATOR"

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
        private const val NAMESPACE_SEPARATOR = "__"
        // Matches macOS Finder's WebDAVFS — well-known by Synology and other DSM-style WebDAV
        // servers, so MKCOL/PUT requests aren't put through unfamiliar-UA gating.
        private const val FINDER_USER_AGENT = "WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)"
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
