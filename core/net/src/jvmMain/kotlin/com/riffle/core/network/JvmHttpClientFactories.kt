package com.riffle.core.network

import io.ktor.client.HttpClient
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the JVM engine client so Android composition roots can share its connection pool and cache
 * without importing OkHttp outside `core:net`.
 */
class JvmHttpClientPool internal constructor(
    internal val okHttpClient: OkHttpClient,
) {
    fun defaultHttpClient(): HttpClient = createDefaultHttpClient(okHttpClient)

    fun streamingHttpClient(): HttpClient = createStreamingHttpClient(okHttpClient)
}

fun createDefaultJvmHttpClientPool(
    cacheDirectory: File,
    cacheSizeBytes: Long,
): JvmHttpClientPool {
    val cache = Cache(cacheDirectory, cacheSizeBytes)
    return JvmHttpClientPool(
        OkHttpClient.Builder()
            .cache(cache)
            .addNetworkInterceptor(EndpointCacheHeadersInterceptor(DEFAULT_HTTP_CACHE_RULES))
            .callTimeout(30, TimeUnit.SECONDS)
            .build(),
    )
}

fun createWebSourceHttpClient(
    cacheDirectory: File,
    cacheSizeBytes: Long,
    maxAgeSeconds: Int,
): HttpClient {
    val cache = Cache(cacheDirectory, cacheSizeBytes)
    val okHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .addNetworkInterceptor(ForceCacheHeadersInterceptor(maxAgeSeconds))
        .addInterceptor(OfflineStaleFallbackInterceptor())
        .build()
    return createDefaultHttpClient(okHttpClient)
}

/**
 * Coil's cover client deliberately has no OkHttp disk cache. Coil's own DiskCache is the sole
 * writer for the image-cache directory, avoiding DiskLruCache journal corruption.
 */
fun createImageLoaderOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .addNetworkInterceptor(COVER_CACHE_CONTROL_INTERCEPTOR)
        .build()

val COVER_CACHE_CONTROL_INTERCEPTOR: Interceptor = Interceptor { chain ->
    chain.proceed(chain.request())
        .newBuilder()
        .header("Cache-Control", "max-age=31536000, immutable")
        .build()
}
