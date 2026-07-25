package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.util.Base64
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
 * [com.riffle.core.models.Source.absUserId]). Using the local `servers.id` here would break
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
    baseUrl: Url,
    username: String,
    password: String,
    private val client: HttpClient,
    private val dispatchers: DispatcherProvider,
) : AnnotationSyncTarget {

    private val basePath: String = ensureTrailingSlash(baseUrl.toString())
    private val authHeader: String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

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
            putFile(annotationFileUrl(namespace, itemId, filename), content, JSON_LD_CONTENT_TYPE, "write $filename")
        }
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        deleteFile(annotationFileUrl(namespace, itemId, filename), "delete $filename")
    }

    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? =
        readFile(deviceMetaUrl(namespace, deviceId))

    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
        withContext(dispatchers.io) {
            putFile(deviceMetaUrl(namespace, deviceId), content, JSON_CONTENT_TYPE, "write device-meta $deviceId")
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
            val url = "$basePath$physical"
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
        try {
            val response = client.request(basePath) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                header("Depth", "0")
                setBody(TextContent(PROPFIND_BODY, ContentType.parse(XML_CONTENT_TYPE)))
            }
            when {
                response.status.value in 200..299 || response.status.value == 207 ->
                    TestConnectionResult.Success
                response.status.value == 401 || response.status.value == 403 ->
                    TestConnectionResult.AuthFailed
                response.status.value == 404 -> tryCreateBase()
                response.status.value in 500..599 ->
                    TestConnectionResult.ServerError(response.status.value)
                else -> TestConnectionResult.ServerError(response.status.value)
            }
        } catch (e: javax.net.ssl.SSLException) {
            TestConnectionResult.TlsError(e.message ?: "TLS error")
        } catch (e: java.io.IOException) {
            TestConnectionResult.NetworkError(e.message ?: "Network error")
        }
    }

    private suspend fun tryCreateBase(): TestConnectionResult = try {
        val response = client.request(basePath) {
            method = HttpMethod("MKCOL")
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
        }
        when {
            response.status.value in 200..299 || response.status.value == 405 ->
                TestConnectionResult.Success
            response.status.value == 401 || response.status.value == 403 ->
                TestConnectionResult.AuthFailed
            else -> TestConnectionResult.ServerError(response.status.value)
        }
    } catch (e: javax.net.ssl.SSLException) {
        TestConnectionResult.TlsError(e.message ?: "TLS error")
    } catch (e: java.io.IOException) {
        TestConnectionResult.NetworkError(e.message ?: "Network error")
    }

    private suspend fun readFile(url: String): String? = withContext(dispatchers.io) {
        classifyWebDavTransportErrors {
            val response = client.get(url) {
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
            }
            when (response.status.value) {
                404 -> null
                401, 403 -> throw AnnotationSyncException.AuthFailed(response.status.value)
                in 200..299 -> response.bodyAsText()
                else -> throw AnnotationSyncException.HttpFailure(response.status.value, "read $url")
            }
        }
    }

    private suspend fun putFile(url: String, content: String, contentType: String, op: String) {
        classifyWebDavTransportErrors {
            val response = client.put(url) {
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                setBody(TextContent(content, ContentType.parse(contentType)))
            }
            when (response.status.value) {
                401, 403 -> throw AnnotationSyncException.AuthFailed(response.status.value)
                in 200..299 -> Unit
                else -> throw AnnotationSyncException.HttpFailure(response.status.value, op)
            }
        }
    }

    private suspend fun deleteFile(url: String, op: String) = withContext(dispatchers.io) {
        classifyWebDavTransportErrors {
            val response = client.delete(url) {
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
            }
            // 404 / 410 / 405 are treated as a no-op success — the file is already gone or the
            // server rejects DELETE on a missing resource, which is what we want either way.
            when (response.status.value) {
                in 200..299, 404, 405, 410 -> Unit
                401, 403 -> throw AnnotationSyncException.AuthFailed(response.status.value)
                else -> throw AnnotationSyncException.HttpFailure(response.status.value, op)
            }
        }
    }

    private suspend fun propfindBaseFilenames(): List<String> = withContext(dispatchers.io) {
        val raw = classifyWebDavTransportErrors {
            val response = client.request(basePath) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, authHeader)
                header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                header("Depth", "1")
                setBody(TextContent(PROPFIND_BODY, ContentType.parse(XML_CONTENT_TYPE)))
            }
            when (response.status.value) {
                400, 404, 405 -> emptyList()
                401, 403 -> throw AnnotationSyncException.AuthFailed(response.status.value)
                207 -> parsePropfindFilenames(response.bodyAsText())
                in 200..299 -> parsePropfindFilenames(response.bodyAsText())
                else -> throw AnnotationSyncException.HttpFailure(response.status.value, "enumerate")
            }
        }
        migrateLegacyAbsNames(raw)
    }

    /**
     * Rename any pre-`abs_` legacy ABS files in-place, so every downstream method
     * (`list`, `enumerateDevices`, `enumerateNamespaces`, `forgetNamespace`) sees the
     * post-migration filenames. MOVE is issued per legacy hit; the returned list swaps in
     * the destination name on success. On any hard failure (network, 5xx), the legacy
     * name is left in the returned list so callers still see the file — worst case a
     * second sync retries the MOVE. Concurrent-device edge cases:
     *  - 404 on the source means a peer already MOVE'd it → treat as success.
     *  - 412 (destination exists) means a peer already wrote the migrated file → DELETE
     *    the orphan legacy source so a subsequent PROPFIND doesn't loop on it forever.
     * Post-mapping the list is `distinct()`-ed to collapse the pair that arises when both
     * `<uuid>__…` and `abs_<uuid>__…` were present in the same PROPFIND.
     * Idempotent: a share with no legacy files does zero extra work.
     */
    private suspend fun migrateLegacyAbsNames(names: List<String>): List<String> {
        if (names.none { LegacyAbsNamespaceMigration.isLegacyAbsFilename(it) }) return names
        val result = ArrayList<String>(names.size)
        for (name in names) {
            if (!LegacyAbsNamespaceMigration.isLegacyAbsFilename(name)) {
                result.add(name)
            } else {
                val target = LegacyAbsNamespaceMigration.migratedName(name)
                result.add(if (migrateOne(name, target)) target else name)
            }
        }
        return result.distinct()
    }

    /**
     * Returns true iff [from] is at or has reached [to] on the server after this call.
     * Handles the four outcomes: MOVE ok (2xx), source already gone (404 — peer got there
     * first), destination already exists (412 — peer wrote it, so DELETE our orphan
     * legacy source), or anything else (leave the file, caller will retry next sync).
     */
    private suspend fun migrateOne(from: String, to: String): Boolean = try {
        val src = "$basePath$from"
        val dst = "$basePath$to"
        val response = client.request(src) {
            method = HttpMethod("MOVE")
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
            header("Destination", dst)
            header("Overwrite", "F")
        }
        when (response.status.value) {
            in 200..299, 201, 204 -> true
            // Peer already MOVEd it. Our source is gone; destination has the content.
            404 -> true
            // Peer already wrote the destination independently. Delete our orphan source
            // so a follow-up PROPFIND doesn't keep trying the same MOVE forever.
            412 -> {
                runCatching {
                    client.delete(src) {
                        header(HttpHeaders.Authorization, authHeader)
                        header(HttpHeaders.UserAgent, FINDER_USER_AGENT)
                    }
                }
                true
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun annotationFileUrl(namespace: String, itemId: String, filename: String): String =
        "$basePath${annotationPrefix(namespace, itemId)}$filename"

    private fun deviceMetaUrl(namespace: String, deviceId: String): String =
        "$basePath$namespace$NAMESPACE_SEPARATOR$DEVICE_META_NAME_PREFIX$deviceId$JSON_SUFFIX"

    /** Composite filename prefix that emulates the per-book directory: `<namespace>__<itemId>__`. */
    private fun annotationPrefix(namespace: String, itemId: String): String =
        "$namespace$NAMESPACE_SEPARATOR$itemId$NAMESPACE_SEPARATOR"

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"

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
        private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"
        private const val JSON_LD_CONTENT_TYPE = "application/ld+json; charset=utf-8"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

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
internal suspend inline fun <T> classifyWebDavTransportErrors(crossinline block: suspend () -> T): T {
    return try {
        block()
    } catch (e: javax.net.ssl.SSLException) {
        throw AnnotationSyncException.TlsError(e.message ?: "TLS error", e)
    } catch (e: java.io.IOException) {
        throw AnnotationSyncException.NetworkError(e.message ?: "network error", e)
    }
}

/** Parse a user-supplied URL; returns null on malformed input. */
internal fun parseWebDavBaseUrl(raw: String): Url? =
    raw.trim().takeIf { it.isNotEmpty() }?.let {
        try { URLBuilder(it).build() } catch (_: Exception) { null }
    }
