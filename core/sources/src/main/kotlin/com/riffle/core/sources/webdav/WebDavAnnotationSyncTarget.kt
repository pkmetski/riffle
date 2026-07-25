package com.riffle.core.sources.webdav

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
 * See `core:data`'s original for the full layout documentation. Moved here so it can be tested
 * with Ktor's [io.ktor.client.engine.mock.MockEngine] without an Android build toolchain.
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
            404 -> true
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
        private const val FINDER_USER_AGENT = "WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)"
        private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"
        private const val JSON_LD_CONTENT_TYPE = "application/ld+json; charset=utf-8"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

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
