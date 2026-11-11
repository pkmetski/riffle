package com.riffle.app.feature.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.UpdateDownloadState
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.shellui.UpdateState

@Composable
internal fun UpdateDrawerRow(
    versionName: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.ui_update_available_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .testTag(TestTags.NAV_DRAWER_UPDATE)
            .clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateAvailableSheet(
    state: UpdateState,
    downloadState: UpdateDownloadState?,
    onUpdate: (update: AvailableUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDownloading = downloadState != null
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { !isDownloading })
    ModalBottomSheet(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ui_update_available_version, state.update.versionName),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.latestChangelog.isNotBlank()) {
                Text(
                    text = state.latestChangelog,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            when (downloadState) {
                is UpdateDownloadState.Downloading ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            progress = { downloadState.percent / 100f },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(stringResource(R.string.ui_download_percent, downloadState.percent), style = MaterialTheme.typography.bodySmall)
                    }
                is UpdateDownloadState.Installing ->
                    Text(
                        stringResource(R.string.ui_starting_installer),
                        style = MaterialTheme.typography.bodySmall,
                    )
                is UpdateDownloadState.Failed ->
                    Button(onClick = { onUpdate(state.update) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_retry))
                    }
                null ->
                    Button(onClick = { onUpdate(state.update) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_download_and_install))
                    }
            }
        }
    }
}
