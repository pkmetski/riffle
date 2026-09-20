package com.riffle.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.sync.ForegroundSyncDriver
import com.riffle.feature.source.ui.RiffleTheme
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

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
    val syncDriver = koinInject<ForegroundSyncDriver>()
    val appBecameActive = koinInject<Flow<Unit>>(qualifier = named(ForegroundSyncDriver.APP_BECAME_ACTIVE))
    val connectivity = koinInject<ConnectivityObserver>()
    val dispatchers = koinInject<DispatcherProvider>()

    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(appearanceCoordinator, systemDark) {
        appearanceCoordinator.setSystemDark(systemDark)
    }
    // Off the composition dispatcher — see runStartupWork. Both jobs are disk/DB/network work
    // and neither may sit on the UI thread at first composition.
    LaunchedEffect(syncDriver, contentCacheCleaner) {
        runStartupWork(
            io = dispatchers.io,
            contentCacheCleaner = contentCacheCleaner,
            syncDriver = syncDriver,
            appBecameActive = appBecameActive,
            isOnline = connectivity.isOnline,
        )
    }

    val appearance by appearanceCoordinator.resolved.collectAsState()
    RiffleTheme(darkTheme = appearance.appChrome.isDark, content = content)
}
