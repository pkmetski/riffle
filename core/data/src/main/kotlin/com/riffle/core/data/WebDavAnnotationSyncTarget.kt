package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
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
 *
 * - Annotation file: `<basePath>/<namespace>__<itemId>__annotations-<deviceId>.jsonld`
 *
 * `namespace` is the cross-device-stable ABS user id (`/api/me` → `user.id`, persisted on
 * [com.riffle.core.domain.Source.absUserId]). Using the local `servers.id` here would break
 * cross-device sync — see [com.riffle.core.domain.AnnotationSyncTarget] kdoc for the full
 * rationale.
 *
 * We *don't* nest the `<sourceId>` / `<itemId>` segments as subdirectories: Synology DSM's
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
    private val dispatchers: DispatcherProvider,
) : AnnotationSyncTarget {

    private val basePath: HttpUrl = ensureTrailingSlash(baseUrl)
    private val authHeader: String = Credentials.basic(username, password)

    override suspend fun list(namespace: String, itemId: String): List<String> =
        withContext(dispatchers.io) {
            // PROPFIND basePath, then filter to entries whose physical name carries the matching
            // `<namespace>__<itemId>__` prefix; return the logical (post-prefix) filename so the
            // controller sees the unprefixed name unchanged.
            val prefix = annotationPrefix(namespace, itemId)
            propfindBaseFilenames()
                .filter { it.startsWith(prefix) && it.endsWith(JSONLD_SUFFIX) }
                .map { it.removePrefix(prefix) }
        }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? =
        readFile(annotationFileUrl(namespace, itemId, filename))

    override suspend fun write(
        namespace: String,
        itemId: String,
        filename: String,
        content: String,
    ) {
        withContext(dispatchers.io) {
            // Flat path under basePath. No per-book subdirectories means MKCOL never has to fire on
            // a real push — the only collection that must exist is basePath itself, which the user
            // has already vouched for via Test Connection (and which we MKCOL-create on demand).
            putFile(annotationFileUrl(namespace, itemId, filename), content, JSON_LD_MEDIA, "write $filename")
        }
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        deleteFile(annotationFileUrl(namespace, itemId, filename), "delete $filename")
    }

    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? =
        readFile(deviceMetaUrl(namespace, deviceId))

    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
        withContext(dispatchers.io) {
            putFile(deviceMetaUrl(namespace, deviceId), content, JSON_MEDIA, "write device-meta $deviceId")
        }
    }

    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {
        deleteFile(deviceMetaUrl(namespace, deviceId), "delete device-meta $deviceId")
    }

    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing =
        withContext(dispatchers.io) {
            val all = propfindBaseFilenames()
            val annotationPrefix = "$namespace$NAMESPACE_SEPARATOR"

            val annotationFiles = mutableMapOf<String, MutableList<AnnotationFileRef>>()

            for (physicalName in all) {
                if (!physicalName.startsWith(annotationPrefix)) continue
                if (!physicalName.endsWith(JSONLD_SUFFIX)) continue
                // <namespace>__<itemId>__annotations-<deviceId>.jsonld
                val afterNamespace = physicalName.removePrefix(annotationPrefix)
                val sepIndex = afterNamespace.indexOf(NAMESPACE_SEPARATOR)
                if (sepIndex <= 0) continue
                val itemId = afterNamespace.substring(0, sepIndex)
                val filename = afterNamespace.substring(sepIndex + NAMESPACE_SEPARATOR.length)
                if (!filename.startsWith(ANNOTATION_NAME_PREFIX) || !filename.endsWith(JSONLD_SUFFIX)) continue
                val deviceId = filename
                    .removePrefix(ANNOTATION_NAME_PREFIX)
                    .removeSuffix(JSONLD_SUFFIX)
                if (deviceId.isEmpty()) continue
                annotationFiles
                    .getOrPut(deviceId) { mutableListOf() }
                    .add(AnnotationFileRef(itemId = itemId, filename = filename))
            }

            val rows = annotationFiles.keys.toSortedSet().map { deviceId ->
                DeviceFileSummary(
                    deviceId = deviceId,
                    annotationFiles = annotationFiles[deviceId]?.toList().orEmpty(),
                )
            }
            NamespaceDeviceListing(devices = rows)
        }

    override suspend fun enumerateNamespaces(): List<NamespaceSummary> =
        withContext(dispatchers.io) {
            val all = propfindBaseFilenames()
            val annotationByNs = mutableMapOf<String, Int>()
            for (physical in all) {
                val sepIdx = physical.indexOf(NAMESPACE_SEPARATOR)
                if (sepIdx <= 0) continue
                val ns = physical.substring(0, sepIdx)
                val tail = physical.substring(sepIdx + NAMESPACE_SEPARATOR.length)
                when {
                    tail.endsWith(JSONLD_SUFFIX) ->
                        annotationByNs[ns] = (annotationByNs[ns] ?: 0) + 1
                    // else: unknown file under base — skip silently.
                }
            }
            annotationByNs.keys.toSortedSet().map { ns ->
                NamespaceSummary(
                    namespace = ns,
                    annotationFileCount = annotationByNs[ns] ?: 0,
                )
            }
        }

    override suspend fun forgetNamespace(namespace: String): Int = withContext(dispatchers.io) {
        val all = propfindBaseFilenames()
        val prefix = "$namespace$NAMESPACE_SEPARATOR"
        var deleted = 0
        for (physical in all) {
            if (!physical.startsWith(prefix)) continue
            // Use the physical filename as the URL segment directly. annotationFileUrl
            // would re-prefix and double-encode the namespace; just hit the literal name we saw.
            val url = basePath.newBuilder().addPathSegment(physical).build()
            try {
                deleteFile(url, "delete $physical")
                deleted++
            } catch (_: Exception) {
                // best-effort bulk delete; continue on per-file failure
            }
        }
        deleted
    }

    suspend fun testConnection(): TestConnectionResult = withContext(dispatchers.io) {
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

    private suspend fun readFile(url: HttpUrl): String? = withContext(dispatchers.io) {
        classifyWebDavTransportErrors {
            val request = baseRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    response.code == 401 || response.code == 403 ->
                        throw AnnotationSyncException.AuthFailed(response.code)
                    !response.isSuccessful ->
                        throw AnnotationSyncException.HttpFailure(response.code, "read ${url.encodedPath}")
                    else -> response.body.string()
                }
            }
        }
    }

    private fun putFile(url: HttpUrl, content: String, media: okhttp3.MediaType, op: String) {
        classifyWebDavTransportErrors {
            val body = content.toRequestBody(media)
            val response = put(url, body)
            val code = response.code
            val ok = response.isSuccessful
            response.close()
            if (!ok) {
                when (code) {
                    401, 403 -> throw AnnotationSyncException.AuthFailed(code)
                    else -> throw AnnotationSyncException.HttpFailure(code, op)
                }
            }
        }
    }

    private suspend fun deleteFile(url: HttpUrl, op: String) = withContext(dispatchers.io) {
        classifyWebDavTransportErrors {
            val request = baseRequest(url).delete().build()
            client.newCall(request).execute().use { response ->
                // 404 / 410 / 405 are treated as a no-op success — the file is already gone or the
                // server rejects DELETE on a missing resource, which is what we want either way.
                when {
                    response.isSuccessful || response.code in setOf(204, 404, 405, 410) -> Unit
                    response.code == 401 || response.code == 403 ->
                        throw AnnotationSyncException.AuthFailed(response.code)
                    else -> throw AnnotationSyncException.HttpFailure(response.code, op)
                }
            }
        }
    }

    private suspend fun propfindBaseFilenames(): List<String> = withContext(dispatchers.io) {
        classifyWebDavTransportErrors {
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
                        throw AnnotationSyncException.HttpFailure(response.code, "enumerate")
                    else -> parsePropfindFilenames(response.body.string())
                }
            }
        }
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

    private fun annotationFileUrl(namespace: String, itemId: String, filename: String): HttpUrl =
        basePath.newBuilder()
            .addPathSegment(annotationPrefix(namespace, itemId) + filename)
            .build()

    private fun deviceMetaUrl(namespace: String, deviceId: String): HttpUrl =
        basePath.newBuilder()
            .addPathSegment("$namespace$NAMESPACE_SEPARATOR$DEVICE_META_NAME_PREFIX$deviceId$JSON_SUFFIX")
            .build()

    /** Composite filename prefix that emulates the per-book directory: `<namespace>__<itemId>__`. */
    private fun annotationPrefix(namespace: String, itemId: String): String =
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
            .filter { it.isNotEmpty() }
            // Synology DSM (and other AFP-aware shares) emits a `._<filename>` AppleDouble
            // shadow alongside every real file. They aren't ours and showing them as
            // separate namespaces in Maintenance is just noise.
            .filter { !it.startsWith("._") }
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
        private const val ANNOTATION_NAME_PREFIX = "annotations-"
        private const val DEVICE_META_NAME_PREFIX = "device-meta-"
        private const val JSONLD_SUFFIX = ".jsonld"
        private const val JSON_SUFFIX = ".json"
        // Matches macOS Finder's WebDAVFS — well-known by Synology and other DSM-style WebDAV
        // servers, so MKCOL/PUT requests aren't put through unfamiliar-UA gating.
        private const val FINDER_USER_AGENT = "WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)"
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private val JSON_LD_MEDIA = "application/ld+json; charset=utf-8".toMediaType()
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

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
sealed class AnnotationSyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(val code: Int) : AnnotationSyncException("WebDAV authentication failed ($code)")
    class HttpFailure(val code: Int, val operation: String) :
        AnnotationSyncException("WebDAV $operation failed with HTTP $code")
    class NetworkError(message: String, cause: Throwable? = null) : AnnotationSyncException(message, cause)
    class TlsError(message: String, cause: Throwable? = null) : AnnotationSyncException(message, cause)
}

/**
 * Re-throw transport-layer failures as typed [AnnotationSyncException]s so the status surface
 * can classify network vs. TLS vs. server-side errors without inspecting exception types.
 * SSLException is a subtype of IOException — catch it first.
 */
internal inline fun <T> classifyWebDavTransportErrors(block: () -> T): T {
    return try {
        block()
    } catch (e: javax.net.ssl.SSLException) {
        throw AnnotationSyncException.TlsError(e.message ?: "TLS error", e)
    } catch (e: java.io.IOException) {
        throw AnnotationSyncException.NetworkError(e.message ?: "network error", e)
    }
}

/** Parse a user-supplied URL; returns null on malformed input. */
internal fun parseWebDavBaseUrl(raw: String): HttpUrl? =
    raw.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
