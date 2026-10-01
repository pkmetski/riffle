package com.riffle.feature.settings.ui

import androidx.compose.runtime.Composable

/**
 * Platform-specific behavior hooks for the shared Settings screen.
 *
 * - Android: AndroidPlatformSettingsHooks (in :app) wires AppLocaleController.
 * - iOS: DefaultPlatformSettingsHooks no-ops until #1151 wires locale switching.
 */
interface PlatformSettingsHooks {
    /** Renders a language-selection row. No-op on iOS until #1151. */
    @Composable
    fun LanguageRow()

    /**
     * True when the platform can download and install an APK update.
     * Android: true (GitHub Releases APK). iOS: false (open release page in browser).
     */
    fun canInstallUpdate(): Boolean
}
