package com.riffle.app.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.UpdateDownloadState

@Composable
internal fun UpdateAvailableDialog(
    state: StartupUpdateDialogState,
    downloadState: UpdateDownloadState?,
    onIgnore: (versionCode: Int) -> Unit,
    onUpdate: (update: AvailableUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDownloading = downloadState != null
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
        ),
        title = { Text("Update available") },
        text = {
            LazyColumn {
                items(state.releases) { release ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "v${release.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = release.changelog.ifBlank { "No release notes." },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (state.releases.last() != release) {
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
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
                        Text("${downloadState.percent}%", style = MaterialTheme.typography.bodySmall)
                    }
                is UpdateDownloadState.Installing ->
                    Text(
                        "Starting installer…",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                is UpdateDownloadState.Failed ->
                    Button(onClick = { onUpdate(state.update) }) { Text("Retry") }
                null ->
                    Button(onClick = { onUpdate(state.update) }) { Text("Update") }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onIgnore(state.update.versionCode) }) {
                        Text("Ignore this version")
                    }
                    TextButton(onClick = onDismiss) { Text("Later") }
                }
            }
        },
    )
}
