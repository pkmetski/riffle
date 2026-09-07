package com.riffle.core.catalog.oreilly

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLQueryComponent

/**
 * Thin authenticated transport over the `learning.oreilly.com` Learning API. Owns URL construction
 * and the session-cookie header; all JSON shapes live in [OReillyDtos] / [OReillyParser].
 *
 * Auth: [cookieHeader] is the **entire** cookie string harvested from the WebView login
 * (`orm-jwt=…; groot_sessionid=…; …`), sent verbatim as the `Cookie` header. Sending only `orm-jwt`
 * is not enough — O'Reilly treats a partial session as anonymous and returns 404 for subscriber
 * content, so the full cookie set is required.
 *
 * This is the network boundary — the reverse-engineered endpoint surface. It is intentionally
 * isolated so parsing/synthesis stay unit-testable without hitting the live service.
 */
internal class OReillyApi(
    private val client: HttpClient,
    private val cookieHeader: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val base = baseUrl.trimEnd('/')

    fun baseUrl(): String = base

    /** JSON API calls (search / spine / files-list / metadata). */
    suspend fun getJson(url: String): String {
        val response = authorizedGet(url, ACCEPT_JSON)
        if (!response.status.isSuccess()) throw OReillyHttpException(response.status.value, url)
        return response.bodyAsText()
    }

    /**
     * Content fetch (chapter HTML). MUST use the HTML Accept, not `application/json`: with
     * `?download=false` + an HTML Accept O'Reilly returns the full chapter, but an `application/json`
     * Accept yields the ~2KB DRM *sample* even with `?download=false`.
     */
    suspend fun getContent(url: String): String {
        val response = authorizedGet(url, ACCEPT_HTML)
        if (!response.status.isSuccess()) throw OReillyHttpException(response.status.value, url)
        return response.bodyAsText()
    }

    /** Binary asset bytes (images / css / fonts). */
    suspend fun getBytes(url: String): ByteArray {
        val response = authorizedGet(url, ACCEPT_ANY)
        if (!response.status.isSuccess()) throw OReillyHttpException(response.status.value, url)
        return response.readBytes()
    }

    suspend fun ping(): Boolean = runCatching {
        authorizedGet("$base/api/v1/user/", ACCEPT_JSON).status.value.let { it in 200..499 }
    }.getOrDefault(false)

    private suspend fun authorizedGet(url: String, accept: String): HttpResponse = client.get(absolute(url)) {
        header("Cookie", cookieHeader)
        header("Accept", accept)
        header("User-Agent", USER_AGENT)
    }

    private fun absolute(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "$base$url"

    // ---- Endpoint builders (reverse-engineered surface) ---------------------

    fun searchUrl(query: String, root: String, page: Int, pageSize: Int): String {
        val q = query.encodeURLQueryComponent()
        val format = if (root == OReillyRoots.AUDIOBOOKS) "audiobook" else "book"
        return "$base/api/v2/search/?query=$q&formats=$format&limit=$pageSize&page=$page"
    }

    /** Default browse: O'Reilly has no folder tree, so an empty-query search seeds a shelf. */
    fun browseUrl(root: String, page: Int, pageSize: Int): String {
        val format = if (root == OReillyRoots.AUDIOBOOKS) "audiobook" else "book"
        return "$base/api/v2/search/?query=*&formats=$format&sort=popularity&limit=$pageSize&page=$page"
    }

    // ---- Real content API (verified live 2026-09) — `/api/v2/epubs/{urn}/…` -----------------

    /** The `urn:orm:book:{id}` the v2 content API keys on (search hits carry it as `ourn`). */
    fun bookUrn(id: String): String = "urn:orm:book:$id"

    fun bookDetailUrl(id: String): String = "$base/api/v2/epubs/${bookUrn(id)}/"

    /** Ordered spine (chapters with titles). Paginated; 100 covers all shipping books. */
    fun spineUrl(id: String): String = "$base/api/v2/epubs/${bookUrn(id)}/spine/?limit=100"

    /** Every packaged file (chapters, images, css, fonts). Paginated. */
    fun filesUrl(id: String, limit: Int = 300, offset: Int = 0): String =
        "$base/api/v2/epubs/${bookUrn(id)}/files/?limit=$limit&offset=$offset"

    /**
     * Raw bytes of one packaged file by its `full_path` (e.g. `xhtml/cover.xhtml`, `images/x.jpg`).
     *
     * `?download=false` is REQUIRED: without it O'Reilly's DRM serves a truncated ~2KB *sample*
     * (the file fetched as a "download"), whereas `download=false` — the in-reader viewing path —
     * returns the full chapter. Verified live: `/files/ch01.html` → 1,994B vs
     * `/files/ch01.html?download=false` → 63,117B.
     */
    fun fileContentUrl(id: String, fullPath: String): String =
        "$base/api/v2/epubs/${bookUrn(id)}/files/$fullPath?download=false"

    /** Prefix that appears as an absolute src in chapter XHTML; rewritten to a relative path. */
    fun filesPrefix(id: String): String = "/api/v2/epubs/${bookUrn(id)}/files/"

    /** High-res cover image. Metadata carries no cover field; the public cover CDN takes a size
     *  segment — `/600/` returns the full-resolution art (~312 KB) vs ~16 KB for the bare path. */
    fun coverUrl(id: String): String = coverUrl(id, base)

    // ---- Audiobook (Kaltura) surface (verified live 2026-09) ----------------
    // O'Reilly audiobooks are Kaltura-hosted "videos"; these are the v1 player endpoints the web
    // reader uses. See [OReillyDtos] for the shapes and OReillyCatalog for the assembly.

    /** Ordered chapter list for an audiobook. Keyed by the bare id (the urn 404s here). */
    fun videoTocUrl(id: String): String = "$base/api/v1/videotocs/$id/"

    /** Resolves one chapter (by its `reference_id`) to its Kaltura entry id. Trailing slash required. */
    fun videoClipUrl(referenceId: String): String = "$base/api/v1/videoclips/$referenceId/"

    /** The Kaltura partner the account streams under. */
    fun kalturaConfigUrl(): String = "$base/api/v1/player/kaltura_config/"

    /** Mints a short-lived (~1h) Kaltura session token. */
    fun kalturaSessionUrl(): String = "$base/api/v1/player/kaltura_session/"

    companion object {
        const val DEFAULT_BASE_URL = "https://learning.oreilly.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
        private const val ACCEPT_JSON = "application/json"
        private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val ACCEPT_ANY = "*/*"

        /** High-resolution cover URL for [id] (public CDN, no auth). Shared by search + detail. */
        fun coverUrl(id: String, base: String = DEFAULT_BASE_URL): String =
            "${base.trimEnd('/')}/library/cover/$id/600/"

        const val KALTURA_CDN = "https://cdnapisec.kaltura.com"
        const val HLS_MIME = "application/x-mpegURL"

        /**
         * Kaltura HLS playManifest URL for one audiobook chapter. The [ks] authorizes playback (no
         * O'Reilly cookie needed on the Kaltura CDN); it expires ~1h after minting, so it is baked in
         * at session-open time. Returns an `.m3u8` with an AAC audio flavor that Media3 plays natively.
         */
        fun kalturaHlsUrl(partnerId: String, entryId: String, ks: String): String =
            "$KALTURA_CDN/p/$partnerId/sp/${partnerId}00/playManifest/entryId/$entryId" +
                "/format/applehttp/protocol/https/a.m3u8?ks=$ks"
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
}

internal class OReillyHttpException(val code: Int, val url: String) :
    RuntimeException("O'Reilly request failed ($code): $url")
