package com.riffle.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.acra.ReportField
import org.acra.ktx.initAcra

@HiltAndroidApp
class RiffleApplication : Application(), ImageLoaderFactory {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra {
            reportContent = listOf(
                ReportField.STACK_TRACE,
                ReportField.PHONE_MODEL,
                ReportField.ANDROID_VERSION,
                ReportField.APP_VERSION_NAME,
                ReportField.AVAILABLE_MEM_SIZE,
            )
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { imageLoaderOkHttpClient() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .build()
}

/** 100 MB cap for the on-disk cover cache. */
internal const val IMAGE_DISK_CACHE_MAX_BYTES = 100L * 1024 * 1024

/**
 * Serves cached covers instantly and revalidates silently after a day; stale copies are acceptable
 * for up to 7 days (covers rarely change). Coil's DiskCache reads these response headers to decide
 * freshness, so the policy applies without an OkHttp disk cache.
 */
internal val coverCacheControlInterceptor = Interceptor { chain ->
    chain.proceed(chain.request())
        .newBuilder()
        .header("Cache-Control", "max-age=86400, stale-while-revalidate=604800")
        .build()
}

/**
 * OkHttp client for the cover [ImageLoader]. Deliberately carries **no** OkHttp `Cache`: Coil's own
 * DiskCache owns `cacheDir/image_cache`. An OkHttp `Cache` and Coil's `DiskCache` are two separate
 * `DiskLruCache` writers, and pointing both at that one directory corrupts the journal
 * ("unexpected journal header"), wiping the cover cache on launch. Keeping a single writer for the
 * directory is the fix — see RiffleImageLoaderTest.
 */
internal fun imageLoaderOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .addNetworkInterceptor(coverCacheControlInterceptor)
        .build()
