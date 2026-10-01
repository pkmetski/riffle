package com.riffle.feature.settings.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.settings.ui.i18n.AppLanguage

/** No-op PlatformSettingsHooks used on iOS until #1151 wires locale switching. */
object DefaultPlatformSettingsHooks : PlatformSettingsHooks {
    @Composable override fun LanguageRow() = Unit
    override fun canInstallUpdate(): Boolean = false
    override fun onLanguageChanged(language: AppLanguage) = Unit
    @Composable override fun OnResumeEffect(block: () -> Unit) = Unit
}
