package com.riffle.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.feature.source.ui.RiffleTheme
import org.koin.compose.koinInject

/**
 * The iOS composition root's appearance + startup wiring, mirroring what `MainActivity` does on
 * Android (`MainActivity.kt:91,115`).
 *
 * Two things were missing before (#1071 §13, §15.1):
 *
 *  - Nothing ever called [AppearanceCoordinator.setSystemDark], so its `systemDark` flag was
 *    pinned `false` and `RiffleTheme` was invoked with no `darkTheme` argument — the App Theme
 *    picker in Settings did nothing and `ReaderTheme.Auto` in app-theme mode always resolved
 *    light.
 *  - `ContentCacheCleaner` had no iOS caller, so "Auto-clear cache after N days" never ran.
 *    iOS has no background execution (`Info.plist` declares only `UIBackgroundModes: audio` and
 *    there is no `BGTaskScheduler`), so the sweep Android schedules as a periodic WorkManager
 *    job runs here instead — once per app start, off the main thread via the cleaner's own
 *    `DispatcherProvider.io`.
 */
@Composable
fun RiffleAppRoot(content: @Composable () -> Unit) {
    val appearanceCoordinator = koinInject<AppearanceCoordinator>()
    val contentCacheCleaner = koinInject<ContentCacheCleaner>()

    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(appearanceCoordinator, systemDark) {
        appearanceCoordinator.setSystemDark(systemDark)
    }
    LaunchedEffect(contentCacheCleaner) {
        // A failed sweep must never take the app down with it; the next launch retries.
        runCatching { contentCacheCleaner.cleanExpired() }
    }

    val appearance by appearanceCoordinator.resolved.collectAsState()
    RiffleTheme(darkTheme = appearance.appChrome.isDark, content = content)
}
