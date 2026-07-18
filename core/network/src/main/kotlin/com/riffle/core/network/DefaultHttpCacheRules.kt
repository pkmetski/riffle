package com.riffle.core.network

/**
 * Cache-Control rules for the shared default `OkHttpClient` used by ABS, Storyteller, and the
 * GitHub releases updater. Rules are conservative: only endpoints whose response is either
 * effectively immutable within the TTL window (server version, user identity, library metadata)
 * or where a stale read is harmless (listening-stats, releases). Progress, session, bookmark, and
 * ebook-binary paths are deliberately absent so a cached copy never masks a live write.
 *
 * TTL rationale:
 *  - `/status` (5m): version banner + connectivity ping. Only changes on server upgrade.
 *  - `/api/me` (15m): user id + prefs. Server-side edits are rare and the app re-fetches on refresh.
 *  - `/api/libraries` (15m): library list. Change requires an admin action.
 *  - `/api/libraries/{id}/series` and `/collections` (10m): shape rarely churns mid-session.
 *  - `/api/me/listening-stats` (60s): stats screen entry cost; a minute-old total is fine.
 *  - GitHub `/repos/{owner}/{repo}/releases` (6h): only checked at launch and behind a manual
 *    refresh; a 6-hour cache saves the round-trip on every cold start.
 */
val DEFAULT_HTTP_CACHE_RULES: List<EndpointCacheHeadersInterceptor.Rule> = listOf(
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/status$"""), maxAgeSeconds = 5 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/me/listening-stats$"""), maxAgeSeconds = 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/me$"""), maxAgeSeconds = 15 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/libraries$"""), maxAgeSeconds = 15 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/libraries/[^/]+/series$"""), maxAgeSeconds = 10 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/libraries/[^/]+/collections$"""), maxAgeSeconds = 10 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/repos/[^/]+/[^/]+/releases$"""), maxAgeSeconds = 6 * 60 * 60),
)
