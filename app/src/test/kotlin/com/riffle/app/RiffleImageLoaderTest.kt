package com.riffle.app

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiffleImageLoaderTest {

    @Test
    fun `cover OkHttp client has no disk Cache so it cannot collide with Coil's DiskCache`() {
        // The bug: an OkHttp Cache at cacheDir/image_cache shares the directory with Coil's own
        // DiskCache, corrupting the DiskLruCache journal. The fix keeps a single writer — no
        // OkHttp Cache on this client. Re-adding `.cache(...)` would reintroduce the corruption.
        assertNull(imageLoaderOkHttpClient().cache)
    }

    @Test
    fun `cover OkHttp client keeps exactly the revalidation network interceptor`() {
        val interceptors = imageLoaderOkHttpClient().networkInterceptors
        assertEquals(listOf(coverCacheControlInterceptor), interceptors)
    }

    @Test
    fun `revalidation interceptor rewrites Cache-Control so Coil can cache and revalidate covers`() {
        val request = Request.Builder().url("https://abs.example/cover.jpg").build()
        val upstream = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            // Server sends no caching headers — without the interceptor covers wouldn't cache.
            .body("img".toResponseBody(null))
            .build()

        val result = coverCacheControlInterceptor.intercept(FakeChain(request, upstream))

        assertEquals("max-age=86400, stale-while-revalidate=604800", result.header("Cache-Control"))
    }

    /** Minimal [Interceptor.Chain] that returns a canned response for the request. */
    private class FakeChain(
        private val request: Request,
        private val response: Response,
    ) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = response
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }
}
