package com.riffle.core.network

/**
 * Cache-Control rules for the shared default `OkHttpClient` used by ABS and the GitHub releases
 * updater. Rules are conservative: only endpoints whose response is either effectively immutable
 * within the TTL window (server version, user identity, library metadata) or where a stale read
 * is harmless (listening-stats). Progress, session, bookmark, and ebook-binary paths are
 * deliberately absent so a cached copy never masks a live write.
 *
 * GitHub `/repos/{owner}/{repo}/releases` is deliberately NOT listed: the only caller is the manual Settings
 * "Check for updates" button, where the user's contract is "check now." A cached response would
 * make the button silently no-op for up to N hours after the first check. There is no automatic
 * launch check to amortize.
 *
 * TTL rationale:
 *  - `/status` (5m): version banner + connectivity ping. Only changes on server upgrade.
 *  - `/api/me` (15m): user id + prefs. Server-side edits are rare and the app re-fetches on refresh.
 *  - `/api/libraries` (15m): library list. Change requires an admin action.
 *  - `/api/libraries/{id}/series` and `/collections` (10m): shape rarely churns mid-session.
 *  - `/api/me/listening-stats` (60s): stats screen entry cost; a minute-old total is fine.
 */
val DEFAULT_HTTP_CACHE_RULES: List<EndpointCacheHeadersInterceptor.Rule> = listOf(
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/status$"""), maxAgeSeconds = 5 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/me/listening-stats$"""), maxAgeSeconds = 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/me$"""), maxAgeSeconds = 15 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/libraries$"""), maxAgeSeconds = 15 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/libraries/[^/]+/series$"""), maxAgeSeconds = 10 * 60),
    EndpointCacheHeadersInterceptor.Rule(Regex("""^/api/libraries/[^/]+/collections$"""), maxAgeSeconds = 10 * 60),
)
