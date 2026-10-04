package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pushes and pulls [WebDavPlaylist] JSON files on a WebDAV share.
 *
 * File layout: `{basePath}{namespace}__playlist_{playlistId}.json`
 * Namespace: source-type slug (e.g. `chitanka`) — same formula as ADR-0063 progress sync.
 * PlaylistId for To Read: `"toread-{libraryId}"` (deterministic, stable across devices).
 *
 * `/` is replaced with `.` in namespace and playlistId segments (Synology WebDAV quirk, same as
 * [WebDavProgressRemote.progressFileUrl]).
 */
class WebDavPlaylistSyncer(
    private val config: AnnotationSyncConfig,
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val authHeader: String = webDavBasicAuthHeader(config.username, config.password)
    private val basePath: String = parseWebDavBaseUrl(config.baseUrl)
        ?.toString()
        ?.let { if (it.endsWith("/")) it else "$it/" }
        ?: ""

    /**
     * Writes [playlist] to WebDAV. Returns the server-authoritative `Last-Modified` epoch-ms
     * (parsed from the response header), falling back to [WebDavPlaylist.lastUpdate] if missing.
     * Returns -1 if the base URL is malformed or the PUT fails.
     */
    suspend fun push(namespace: String, playlist: WebDavPlaylist): Long {
        if (basePath.isEmpty()) return -1L
        val url = fileUrl(namespace, playlist.id)
        val body = json.encodeToString(playlist)
        val response = httpClient.request(url) {
            method = HttpMethod.Put
            header("Authorization", authHeader)
            header("User-Agent", WEBDAV_USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) return -1L
        val lastModified = response.headers["Last-Modified"]?.let { parseHttpDate(it) }
        return lastModified ?: playlist.lastUpdate
    }

    /**
     * Fetches the playlist for [playlistId] in [namespace]. Returns null if the file does not
     * exist (404), on any other non-success status, or if the base URL is malformed.
     */
    suspend fun pull(namespace: String, playlistId: String): WebDavPlaylist? {
        if (basePath.isEmpty()) return null
        val url = fileUrl(namespace, playlistId)
        val response = httpClient.request(url) {
            method = HttpMethod.Get
            header("Authorization", authHeader)
            header("User-Agent", WEBDAV_USER_AGENT)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) return null
        return runCatching { json.decodeFromString<WebDavPlaylist>(response.bodyAsText()) }.getOrNull()
    }

    /**
     * Lists all playlists for [namespace] by issuing a PROPFIND and fetching each matching file.
     * Returns an empty list if the base URL is malformed or the PROPFIND returns 404.
     */
    suspend fun list(namespace: String): List<WebDavPlaylist> {
        if (basePath.isEmpty()) return emptyList()
        val propfindResponse = httpClient.request(basePath) {
            method = HttpMethod("PROPFIND")
            header("Authorization", authHeader)
            header("User-Agent", WEBDAV_USER_AGENT)
            header("Depth", "1")
            contentType(ContentType.parse(WEBDAV_XML_CONTENT_TYPE))
            setBody(WEBDAV_PROPFIND_BODY)
        }
        if (propfindResponse.status == HttpStatusCode.NotFound) return emptyList()
        if (!propfindResponse.status.isSuccess()) return emptyList()
        val xml = propfindResponse.bodyAsText()
        val prefix = "${namespace.safeSegment()}${NAMESPACE_SEPARATOR}playlist_"
        val suffix = ".json"
        val filenames = parsePropfindFilenames(xml)
        val matchingIds = filenames
            .filter { it.startsWith(prefix) && it.endsWith(suffix) }
            .map { it.removePrefix(prefix).removeSuffix(suffix) }
        return matchingIds.mapNotNull { pull(namespace, it) }
    }

    /** Deletes the playlist file for [playlistId] in [namespace]. Ignores response status. */
    suspend fun delete(namespace: String, playlistId: String) {
        if (basePath.isEmpty()) return
        httpClient.request(fileUrl(namespace, playlistId)) {
            method = HttpMethod.Delete
            header("Authorization", authHeader)
            header("User-Agent", WEBDAV_USER_AGENT)
        }
    }

    private fun fileUrl(namespace: String, playlistId: String): String {
        val safeNs = namespace.safeSegment()
        val safeId = playlistId.safeSegment()
        return "${basePath}${safeNs}${NAMESPACE_SEPARATOR}playlist_${safeId}.json"
    }

    private fun String.safeSegment() = replace('/', '.')

    companion object {
        /** Builds the deterministic playlist ID for a library's To Read list. */
        fun toReadPlaylistId(libraryId: String) = "toread-$libraryId"
    }
}
