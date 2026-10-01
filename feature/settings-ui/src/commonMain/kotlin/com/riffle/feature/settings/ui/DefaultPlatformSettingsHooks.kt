package com.riffle.feature.settings.ui

import androidx.compose.runtime.Composable

/** No-op PlatformSettingsHooks used on iOS until #1151 wires locale switching. */
object DefaultPlatformSettingsHooks : PlatformSettingsHooks {
    @Composable override fun LanguageRow() = Unit
    override fun canInstallUpdate(): Boolean = false
}
