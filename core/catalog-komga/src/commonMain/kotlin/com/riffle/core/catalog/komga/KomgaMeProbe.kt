package com.riffle.core.catalog.komga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Result of a `GET /api/v{2,1}/users/me` probe against a Komga server.
 *
 * @property status HTTP status of the last request Komga answered; ordinary auth logic treats
 *   `200..399` as authenticated. Callers that need to distinguish `401 vs. transport error`
 *   should catch [java.io.IOException] around [probeKomgaUserId] separately — this shape only
 *   carries statuses the server actually returned.
 * @property userId the parsed `id` field from a 2xx body, or `null` when the status is non-2xx
 *   OR the body was 2xx but did not decode as `{id: String}` (mis-versioned or reverse-proxied
 *   Komga). Callers that persisted a null id should retry on the next opportunity.
 */
data class KomgaMeProbeResult(val status: Int, val userId: String?)

/**
 * Shared `/users/me` probe used by both the add-source authenticator and the annotation-sync
 * remote-user-id resolver (#529). Fixes the drift between the two prior implementations:
 *  - Falls back to `/api/v1/users/me` ONLY on 404 (older Komga builds without the v2 endpoint).
 *    A 401 stays a 401 — no doubling of failed requests on stale credentials.
 *  - Propagates [kotlinx.coroutines.CancellationException] so cancelling the enclosing scope
 *    actually cancels the coroutine (previously masked by a broad `catch (_: Throwable)`).
 *  - Treats a 2xx-with-unparsable body as `userId = null` so auth still succeeds against
 *    misconfigured proxies — the resolver will re-probe on the next reader open.
 *
 * IOException/SSLHandshakeException are surfaced to the caller so they can decide whether it
 * means "network error, try again" or "wrong credentials, tell the user".
 */
suspend fun probeKomgaUserId(http: KomgaHttpClient, baseUrl: String): KomgaMeProbeResult {
    val base = baseUrl.trimEnd('/')
    val v2 = fetchMe(http, "$base/api/v2/users/me")
    return if (v2.status == 404) fetchMe(http, "$base/api/v1/users/me") else v2
}

private suspend fun fetchMe(http: KomgaHttpClient, url: String): KomgaMeProbeResult {
    // `getString` throws KomgaHttpException on non-2xx (carrying the status code) and IOException
    // on transport failure. We catch the former to surface the status, but let the latter (and
    // CancellationException) propagate up — the caller distinguishes auth failure from network
    // failure and must be able to observe cancellation.
    return try {
        val body = http.getString(url)
        val id = runCatching {
            probeJson.decodeFromString(KomgaMeDto.serializer(), body).id.takeIf { it.isNotBlank() }
        }.getOrNull()
        KomgaMeProbeResult(status = 200, userId = id)
    } catch (e: KomgaHttpException) {
        KomgaMeProbeResult(status = e.code, userId = null)
    }
}

private val probeJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class KomgaMeDto(@SerialName("id") val id: String)
