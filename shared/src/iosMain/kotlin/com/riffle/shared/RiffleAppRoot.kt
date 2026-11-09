package com.riffle.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.riffle.core.data.localfiles.OpenInImportFeed
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.localfiles.LocalFilesScannerInterface
import com.riffle.core.logging.Logger
import com.riffle.core.models.SourceType
import com.riffle.core.sync.ForegroundSyncDriver
import com.riffle.feature.designsystem.LocalCoverLoadReporter
import com.riffle.feature.designsystem.LoggingCoverLoadReporter
import com.riffle.feature.designsystem.RiffleTheme
import com.riffle.feature.source.ui.OpenInImportMessages
import com.riffle.feature.source.ui.RiffleMessageScaffold
import com.riffle.feature.source.ui.rememberTransientMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform

/**
 * The iOS composition root's appearance + startup wiring, mirroring what `MainActivity` and
 * `RiffleApplication` do on Android (`MainActivity.kt:91,115`, `RiffleApplication.kt:96-119`).
 *
 * Three things were missing before (#1071 §13, §14, §15.1):
 *
 *  - Nothing ever called [AppearanceCoordinator.setSystemDark], so its `systemDark` flag was
 *    pinned `false` and `RiffleTheme` was invoked with no `darkTheme` argument — the App Theme
 *    picker in Settings did nothing and `ReaderTheme.Auto` in app-theme mode always resolved
 *    light.
 *  - `ContentCacheCleaner` had no iOS caller, so "Auto-clear cache after N days" never ran.
 *  - **Nothing ever retried a failed sync.** Session close was iOS's only push trigger, so a
 *    reading position or audiobook bookmark that went dirty while offline stayed dirty until the
 *    user happened to reopen that exact book while online. [ForegroundSyncDriver] is the honest
 *    replacement for Android's WorkManager jobs: it sweeps at app start, on every foreground, and
 *    on the validated offline→online edge.
 *
 * iOS has no background execution at all — `Info.plist` declares only `UIBackgroundModes: audio`
 * and there is no `BGTaskScheduler` registration — so all of this runs in the foreground, which
 * is why it lives here rather than in a scheduler.
 */
@Composable
fun RiffleAppRoot(content: @Composable () -> Unit) {
    val appearanceCoordinator = koinInject<AppearanceCoordinator>()
    val contentCacheCleaner = koinInject<ContentCacheCleaner>()
    val appBecameActive = koinInject<Flow<Unit>>(qualifier = named(ForegroundSyncDriver.APP_BECAME_ACTIVE))
    val connectivity = koinInject<ConnectivityObserver>()
    val dispatchers = koinInject<DispatcherProvider>()
    val openInImportFeed = koinInject<OpenInImportFeed>()

    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(appearanceCoordinator, systemDark) {
        appearanceCoordinator.setSystemDark(systemDark)
    }
    // ForegroundSyncDriver is resolved on Default rather than in composition — its dependency chain
    // includes AnnotationSyncConfigStoreImpl which reads 3 Keychain items in its constructor, a
    // blocking syscall that takes 1–5 s each on first access. Resolving in composition (Main) was
    // freezing the entire UI at app launch.
    LaunchedEffect(Unit) {
        val syncDriver = withContext(Dispatchers.Default) {
            KoinPlatform.getKoin().get<ForegroundSyncDriver>()
        }
        launch {
            runStartupWork(
                io = dispatchers.io,
                contentCacheCleaner = contentCacheCleaner,
                syncDriver = syncDriver,
                appBecameActive = appBecameActive,
                isOnline = connectivity.isOnline,
            )
        }
        // Auto-rescan local-file folders on every foreground, mirroring Android's
        // LocalFilesFolderWatcher.lifecycleObserver.onStart(). Each scan is idempotent; fires
        // asynchronously so the foreground event is not delayed.
        val localFilesScanner = KoinPlatform.getKoin().get<LocalFilesScannerInterface>()
        val sourceRepository = KoinPlatform.getKoin().get<SourceRepository>()
        withContext(dispatchers.io) {
            appBecameActive.collect {
                runCatching {
                    val sources = sourceRepository.observeAll().first()
                    sources.filter { it.type == SourceType.LOCAL_FILES }.forEach { source ->
                        launch { runCatching { localFilesScanner.scan(source.id) } }
                    }
                }
            }
        }
    }

    val appearance by appearanceCoordinator.resolved.collectAsState()
    // iOS logged nothing at all about cover fetches, which is what made "the cover is blank
    // offline" undiagnosable there. Same reporter and same RIFFLE_COVERS line as Android.
    val coverLogger = koinInject<Logger>()
    val coverReporter = remember(coverLogger) { LoggingCoverLoadReporter(coverLogger) }
    RiffleTheme(darkTheme = appearance.appChrome.isDark) {
        CompositionLocalProvider(LocalCoverLoadReporter provides coverReporter) {
            // "Open in Riffle" starts before any screen exists — the file arrives with the launch
            // that opened the app — so the outcome has to be drained at the composition root or it
            // is never seen at all. This is also the only SnackbarHost on the platform; screens that
            // want one nest their own.
            val messages = rememberTransientMessages()
            OpenInImportMessages(feed = openInImportFeed, messages = messages)
            RiffleMessageScaffold(messages, content = content)
        }
    }
}
