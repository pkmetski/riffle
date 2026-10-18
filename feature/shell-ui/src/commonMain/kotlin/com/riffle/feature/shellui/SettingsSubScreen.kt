package com.riffle.feature.shellui

import com.riffle.feature.source.ui.AddSourceBackend

internal sealed interface SettingsSubScreen {
    data class AddSource(
        val backend: AddSourceBackend? = null,
        val editId: String? = null,
    ) : SettingsSubScreen

    data class WebdavAddSource(val editId: String? = null) : SettingsSubScreen

    data object AnnotationsSync : SettingsSubScreen
    data object AnnotationSyncMaintenance : SettingsSubScreen
    data object ReadaloudSettings : SettingsSubScreen
    data class ReadaloudMatches(val sourceId: String) : SettingsSubScreen
    data object Changelog : SettingsSubScreen
    data object DeveloperOptions : SettingsSubScreen
    data object DebugLogs : SettingsSubScreen
    data object DictionaryPacks : SettingsSubScreen
}
