package com.riffle.feature.settings

import com.riffle.core.domain.AvailableUpdate

/** Drives the "App version" settings row: an inline status that the user advances by tapping. */
sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class UpdateAvailable(
        val versionName: String,
        val update: AvailableUpdate,
    ) : AppUpdateUiState
    data class Downloading(val percent: Int) : AppUpdateUiState
    /** APK downloaded; the system installer has been launched. */
    data object Installing : AppUpdateUiState
    data class Failed(val message: String) : AppUpdateUiState
}
