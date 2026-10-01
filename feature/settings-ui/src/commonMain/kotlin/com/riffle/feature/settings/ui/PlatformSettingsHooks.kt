package com.riffle.feature.settings.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.settings.ui.i18n.AppLanguage

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

    /** Called when the user selects a new app language. No-op on iOS until #1151. */
    fun onLanguageChanged(language: AppLanguage)

    /** Runs [block] whenever the screen resumes (e.g. returns from background). */
    @Composable
    fun OnResumeEffect(block: () -> Unit)
}
