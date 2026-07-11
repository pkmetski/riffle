package com.riffle.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Rewrites successful upstream responses to `Cache-Control: public, max-age=<maxAgeSeconds>`
 * so OkHttp's [okhttp3.Cache] treats them as cacheable for [maxAgeSeconds] regardless of
 * whether the origin sent cache headers. chitanka.info and gramofonche.chitanka.info emit
 * none — without this, the disk cache would never store their responses (ADR 0043).
 *
 * Registered as a network interceptor (not application) so it runs after the response
 * comes off the wire and the modified headers are what OkHttp caches. Non-2xx responses
 * pass through untouched — we don't want to cache 4xx/5xx.
 */
class ForceCacheHeadersInterceptor(private val maxAgeSeconds: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Cache-Control")
            .header("Cache-Control", "public, max-age=$maxAgeSeconds")
            .build()
    }
}
