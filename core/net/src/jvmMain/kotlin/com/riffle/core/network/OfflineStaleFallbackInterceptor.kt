package com.riffle.core.network

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * On [IOException] during a network attempt (offline, DNS failure, connection reset,
 * timeout), retries the same request with `Cache-Control: only-if-cached, max-stale=∞`
 * so OkHttp serves any previously-cached copy regardless of age. If nothing was cached,
 * the retry surfaces a synthetic 504 which we swap back for the original IOException —
 * a genuinely-uncached, offline URL still fails, but anything the user has visited
 * before remains accessible offline (ADR 0043).
 *
 * Registered as an application interceptor so retry can issue a new request through the
 * cache layer. Only [IOException] triggers fallback — non-2xx from the origin means we
 * *did* reach the network and the response body is meaningful.
 */
class OfflineStaleFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        return try {
            chain.proceed(original)
        } catch (io: IOException) {
            val staleRequest = original.newBuilder()
                .cacheControl(
                    CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(Int.MAX_VALUE, TimeUnit.SECONDS)
                        .build()
                )
                .build()
            val cached = try {
                chain.proceed(staleRequest)
            } catch (_: IOException) {
                throw io
            }
            if (cached.code == 504) {
                cached.close()
                throw io
            }
            cached
        }
    }
}
