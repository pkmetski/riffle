package com.riffle.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.riffle.app.sync.kickSweepsOnReconnect
import com.riffle.core.data.AnnotationSweep
import com.riffle.core.data.LocalStoreMigrator
import com.riffle.core.data.ProgressSweep
import com.riffle.core.domain.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.limiter
import org.acra.ktx.initAcra
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class RiffleApplication : Application(), ImageLoaderFactory {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MigratorEntryPoint {
        fun localStoreMigrator(): LocalStoreMigrator
        fun connectivityObserver(): com.riffle.core.domain.ConnectivityObserver
        fun appUpdateRepository(): com.riffle.core.domain.AppUpdateRepository
        fun applicationScope(): ApplicationScope
        fun annotationSweep(): AnnotationSweep
        fun progressSweep(): ProgressSweep
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
            // Show a confirmation on next launch so the user knows the app crashed and can
            // open Settings → Crash reports to share the details. Without this, the only
            // signal was the process restart itself.
            dialog {
                text = "Riffle crashed. Open Settings → Crash reports to view or share the details."
                title = "Crash report"
                positiveButtonText = "OK"
                negativeButtonText = "Dismiss"
            }
            // Cap retention so a looping bug can't fill the disk between launches. Pairs with
            // FileCrashReportSender.pruneToMax — Limiter bounds the upstream queue, the sender
            // bounds the on-disk archive.
            limiter {
                period = 7
                periodUnit = TimeUnit.DAYS
                overallLimit = 50
                stacktraceLimit = 5
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(this, MigratorEntryPoint::class.java)
        val applicationScope = entryPoint.applicationScope()

        // One-time relocation of legacy flat cache/download files into per-Server dirs (ADR 0025).
        // Idempotent and best-effort; runs off the main thread and never blocks startup.
        val migrator = entryPoint.localStoreMigrator()
        applicationScope.launchSurvivable {
            runCatching { migrator.migrate() }
        }

        // Reclaim any update APK left behind by a previous self-update: a successful install restarts
        // the app before the download flow can delete it, so we sweep the update cache on launch.
        val appUpdate = entryPoint.appUpdateRepository()
        applicationScope.launchSurvivable {
            runCatching { appUpdate.sweepStaleApks() }
        }

        // Durable offline progress reconcile (ADR 0030): a foreground kick to flush any progress made
        // offline (waits for connectivity via the worker's CONNECTED constraint), plus the periodic
        // safety net for progress on a book that is never reopened.
        com.riffle.app.sync.ProgressSyncScheduler.sweepNow(this)
        com.riffle.app.sync.ProgressSyncScheduler.ensurePeriodic(this)
        // Durable offline annotation reconcile (ADR 0036): symmetric with progress.
        com.riffle.app.sync.AnnotationSyncScheduler.sweepNow(this)
        com.riffle.app.sync.AnnotationSyncScheduler.ensurePeriodic(this)

        // Flush promptly when connectivity returns mid-session (offline edits made while the app kept
        // running would otherwise wait for the periodic sweep). We run the sweeps INLINE rather than
        // going through the WorkManager schedulers because those schedulers gate on OS-level
        // `NetworkType.CONNECTED`, the same raw signal PR #402's ValidatedNetworkTracker was built to
        // work around. On flaky devices (Huawei / Android 13 / captive portals) the validated
        // observer can already report "online" while the OS constraint still holds the queued sweep
        // — leaving "will retry automatically when connectivity returns" as a broken promise. The
        // validated edge is authoritative.
        val connectivity = entryPoint.connectivityObserver()
        val annotationSweep = entryPoint.annotationSweep()
        val progressSweep = entryPoint.progressSweep()
        applicationScope.launchSurvivable {
            kickSweepsOnReconnect(
                isOnline = connectivity.isOnline,
                runProgressSweep = { runCatching { progressSweep.run() } },
                runAnnotationSweep = { runCatching { annotationSweep.run() } },
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
 * Treats covers as immutable so Coil's DiskCache serves them without ever needing the network —
 * critical for offline mode, where any revalidation would fail and leave cells blank. Safe because
 * cover URLs embed the item's `updatedAt` as `?t=…` (see `LibraryRepositoryImpl.absCoverUrl`): a
 * real cover change in ABS bumps `updatedAt`, producing a new URL and a fresh Coil cache key.
 */
internal val coverCacheControlInterceptor = Interceptor { chain ->
    chain.proceed(chain.request())
        .newBuilder()
        .header("Cache-Control", "max-age=31536000, immutable")
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
