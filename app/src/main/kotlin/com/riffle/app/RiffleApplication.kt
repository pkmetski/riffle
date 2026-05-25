package com.riffle.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.acra.ReportField
import org.acra.ktx.initAcra
import java.io.File

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
            .okHttpClient {
                OkHttpClient.Builder()
                    .cache(Cache(File(cacheDir, "image_cache"), 100L * 1024 * 1024))
                    .addNetworkInterceptor { chain ->
                        // Serve cached covers instantly; revalidate silently after 1 day.
                        // Stale copies are acceptable for up to 7 days (covers rarely change).
                        chain.proceed(chain.request())
                            .newBuilder()
                            .header("Cache-Control", "max-age=86400, stale-while-revalidate=604800")
                            .build()
                    }
                    .build()
            }
            .build()
}
