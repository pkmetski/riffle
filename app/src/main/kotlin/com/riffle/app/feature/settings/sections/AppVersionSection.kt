package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.settings.AppUpdateUiState
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader

/**
 * "App version" section — the manual update-check row. Advances through
 * check → (up-to-date | update available → download % → install) as the user taps.
 */
@Composable
internal fun AppVersionSection(
    installedVersionName: String,
    state: AppUpdateUiState,
    autoUpdateEnabled: Boolean,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
    onNavigateToChangelog: () -> Unit,
    onVersionTap: () -> Unit = {},
) {
    SettingsSectionHeader(stringResource(R.string.ui_app_version_section))
    val supporting = when (state) {
        is AppUpdateUiState.Idle -> stringResource(R.string.ui_installed_version, installedVersionName)
        is AppUpdateUiState.Checking -> stringResource(R.string.ui_checking_for_updates)
        is AppUpdateUiState.UpToDate -> stringResource(R.string.ui_installed_version_up_to_date, installedVersionName)
        is AppUpdateUiState.UpdateAvailable -> stringResource(R.string.ui_update_available_version, state.versionName)
        is AppUpdateUiState.Downloading -> stringResource(R.string.ui_downloading_update_percent, state.percent)
        is AppUpdateUiState.Installing -> stringResource(R.string.ui_starting_installer)
        is AppUpdateUiState.Failed -> stringResource(R.string.ui_update_check_failed, state.message)
    }
    ListItem(
        headlineContent = {
            Column {
                Text(stringResource(R.string.app_name))
                Text(supporting)
            }
        },
        modifier = Modifier.clickable(onClick = onVersionTap),
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
                    Button(onClick = onInstallUpdate) { Text(stringResource(R.string.ui_update)) }
                is AppUpdateUiState.Failed ->
                    TextButton(onClick = onCheckForUpdate) { Text(stringResource(R.string.ui_retry)) }
                else ->
                    TextButton(onClick = onCheckForUpdate) { Text(stringResource(R.string.ui_check_for_updates)) }
            }
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.ui_check_for_updates_automatically)) },
        trailingContent = {
            Switch(
                checked = autoUpdateEnabled,
                onCheckedChange = onSetAutoUpdateEnabled,
            )
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.ui_release_history)) },
        trailingContent = { DrillInChevron() },
        modifier = Modifier.clickable(onClick = onNavigateToChangelog),
    )
}
