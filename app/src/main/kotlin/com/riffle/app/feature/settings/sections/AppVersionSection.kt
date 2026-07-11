package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.settings.AppUpdateUiState
import com.riffle.app.feature.settings.SettingsSectionHeader

/**
 * "App version" section — the manual update-check row. Advances through
 * check → (up-to-date | update available → download % → install) as the user taps.
 */
@Composable
internal fun AppVersionSection(
    installedVersionName: String,
    state: AppUpdateUiState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    SettingsSectionHeader("App version")
    val supporting = when (state) {
        is AppUpdateUiState.Idle -> "Installed: v$installedVersionName"
        is AppUpdateUiState.Checking -> "Checking for updates…"
        is AppUpdateUiState.UpToDate -> "Installed: v$installedVersionName · Up to date"
        is AppUpdateUiState.UpdateAvailable -> "Update available: v${state.versionName}"
        is AppUpdateUiState.Downloading -> "Downloading update… ${state.percent}%"
        is AppUpdateUiState.Installing -> "Starting installer…"
        is AppUpdateUiState.Failed -> "Update check failed: ${state.message}"
    }
    ListItem(
        headlineContent = { Text("Riffle") },
        supportingContent = { Text(supporting) },
        trailingContent = {
            when (state) {
                is AppUpdateUiState.Checking ->
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                is AppUpdateUiState.Downloading ->
                    CircularProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                is AppUpdateUiState.Installing -> {}
                is AppUpdateUiState.UpdateAvailable ->
                    Button(onClick = onInstallUpdate) { Text("Update") }
                is AppUpdateUiState.Failed ->
                    TextButton(onClick = onCheckForUpdate) { Text("Retry") }
                else ->
                    TextButton(onClick = onCheckForUpdate) { Text("Check for updates") }
            }
        },
    )
}
