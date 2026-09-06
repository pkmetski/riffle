package com.riffle.feature.settings

/** Injected app version so SettingsViewModel can live in commonMain without BuildConfig. */
data class AppVersion(val name: String, val code: Int)
