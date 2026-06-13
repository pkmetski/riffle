package com.riffle.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.riffle.core.data.LocalStoreMigrator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.acra.ReportField
import org.acra.ktx.initAcra

@HiltAndroidApp
class RiffleApplication : Application(), ImageLoaderFactory {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MigratorEntryPoint {
        fun localStoreMigrator(): LocalStoreMigrator
        fun connectivityObserver(): com.riffle.core.domain.ConnectivityObserver
        fun appUpdateRepository(): com.riffle.core.domain.AppUpdateRepository
    }

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

    override fun onCreate() {
        super.onCreate()
        // One-time relocation of legacy flat cache/download files into per-Server dirs (ADR 0025).
        // Idempotent and best-effort; runs off the main thread and never blocks startup.
        val migrator = EntryPointAccessors.fromApplication(this, MigratorEntryPoint::class.java).localStoreMigrator()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { migrator.migrate() }
        }

        // Reclaim any update APK left behind by a previous self-update: a successful install restarts
        // the app before the download flow can delete it, so we sweep the update cache on launch.
        val appUpdate = EntryPointAccessors.fromApplication(this, MigratorEntryPoint::class.java).appUpdateRepository()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { appUpdate.sweepStaleApks() }
        }

        // Durable offline progress reconcile (ADR 0030): a foreground kick to flush any progress made
        // offline (waits for connectivity via the worker's CONNECTED constraint), plus the periodic
        // safety net for progress on a book that is never reopened.
        com.riffle.app.sync.ProgressSyncScheduler.sweepNow(this)
        com.riffle.app.sync.ProgressSyncScheduler.ensurePeriodic(this)

        // Flush promptly when connectivity returns mid-session (offline edits made while the app kept
        // running would otherwise wait for the periodic sweep). Skip the initial value; sweep on each
        // false→true transition.
        val connectivity = EntryPointAccessors.fromApplication(this, MigratorEntryPoint::class.java).connectivityObserver()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var wasOnline = connectivity.isOnline.value
            connectivity.isOnline.collect { online ->
                if (online && !wasOnline) com.riffle.app.sync.ProgressSyncScheduler.sweepNow(this@RiffleApplication)
                wasOnline = online
            }
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
