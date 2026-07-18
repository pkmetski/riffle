package com.riffle.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor that stamps `Cache-Control: public, max-age=<n>` on successful responses
 * whose path matches one of the supplied rules. Non-2xx and non-matching requests pass through
 * untouched, so writes and error responses are never cached.
 *
 * Rules are evaluated in order; the first match wins. The path passed to each rule is the URL's
 * `encodedPath` (no query string), lowercased. Query-parameter differences still key separate
 * cache entries because OkHttp caches on the full URL.
 *
 * Used for API endpoints whose origin sends `Cache-Control: private` or no cache header at all
 * (Audiobookshelf, GitHub Releases). Non-cacheable endpoints on the same client — writes, session
 * calls, binary streams — simply don't match any rule and behave as before.
 */
class EndpointCacheHeadersInterceptor(
    private val rules: List<Rule>,
) : Interceptor {

    data class Rule(val pattern: Regex, val maxAgeSeconds: Int)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        if (request.method != "GET") return response
        val path = request.url.encodedPath.lowercase()
        val rule = rules.firstOrNull { it.pattern.containsMatchIn(path) } ?: return response
        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Cache-Control")
            .header("Cache-Control", "public, max-age=${rule.maxAgeSeconds}")
            .build()
    }
}
