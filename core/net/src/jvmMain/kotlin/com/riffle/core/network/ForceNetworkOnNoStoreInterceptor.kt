package com.riffle.core.network

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response

/** Ensures explicit no-store reads cannot be satisfied by the shared disk cache. */
class ForceNetworkOnNoStoreInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return if (request.header("Cache-Control")?.contains("no-store") == true) {
            // FORCE_NETWORK still permits OkHttp to revalidate an existing cached response.
            // ABS can answer that conditional request with a misleading 304/merged 200 while
            // its library index is changing, leaving reconciliation with an old item list.
            // Remove validators as well as the cache directive so this read gets a new body.
            chain.proceed(
                request.newBuilder()
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .removeHeader("If-None-Match")
                    .removeHeader("If-Modified-Since")
                    .build(),
            )
        } else {
            chain.proceed(request)
        }
    }
}
