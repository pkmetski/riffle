package com.riffle.feature.settings.ui

import com.riffle.feature.source.ui.AddSourceBackend

data class SettingsActions(
    val onNavigateBack: () -> Unit,
    val onNavigateToAddSource: (backend: AddSourceBackend, editId: String?) -> Unit,
    val onNavigateToAddSourcePicker: () -> Unit,
    val onNavigateToAddLocalFolder: () -> Unit,
    val onNavigateToReadaloudSettings: () -> Unit,
    val onNavigateToAnnotationsSyncSettings: () -> Unit,
    val onNavigateToDeveloperOptions: () -> Unit,
    val onNavigateToDictionaryPacks: () -> Unit,
    val onNavigateToDebugLogs: () -> Unit,
    val onNavigateToChangelog: () -> Unit,
    val onNavigateToReadaloudMatches: (String) -> Unit,
    val platformHooks: PlatformSettingsHooks,
)

fun SettingsActions.probeFields(): List<Pair<String, Any>> = listOf(
    "onNavigateBack" to onNavigateBack,
    "onNavigateToAddSource" to onNavigateToAddSource,
    "onNavigateToAddSourcePicker" to onNavigateToAddSourcePicker,
    "onNavigateToAddLocalFolder" to onNavigateToAddLocalFolder,
    "onNavigateToReadaloudSettings" to onNavigateToReadaloudSettings,
    "onNavigateToAnnotationsSyncSettings" to onNavigateToAnnotationsSyncSettings,
    "onNavigateToDeveloperOptions" to onNavigateToDeveloperOptions,
    "onNavigateToDictionaryPacks" to onNavigateToDictionaryPacks,
    "onNavigateToDebugLogs" to onNavigateToDebugLogs,
    "onNavigateToChangelog" to onNavigateToChangelog,
    "onNavigateToReadaloudMatches" to onNavigateToReadaloudMatches,
)
