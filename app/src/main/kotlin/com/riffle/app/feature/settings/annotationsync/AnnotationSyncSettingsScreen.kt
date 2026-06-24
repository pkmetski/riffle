package com.riffle.app.feature.settings.annotationsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationSyncSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnnotationSyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.closeRequests.collect { onNavigateBack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotation Sync") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Sync highlights, notes, and bookmarks between your devices.",
                style = MaterialTheme.typography.bodySmall,
            )

            ConnectionSection(state, viewModel)

            // Maintenance only after a config has been saved (NoNamespace ≠ Hidden — when sync is
            // configured but no logged-in server has an absUserId yet, the section appears with a
            // hint instead of silently hiding).
            if (state.maintenance !is MaintenanceUiState.Hidden) {
                HorizontalDivider()
                MaintenanceSection(state, viewModel)
            }
        }
    }

    state.pendingForget?.let { row ->
        ForgetDialog(
            row = row,
            onCancel = viewModel::onForgetCancelled,
            onConfirm = viewModel::onForgetConfirmed,
        )
    }
    if (state.showCompactDialog) {
        CompactDialog(
            onCancel = viewModel::onCompactCancelled,
            onConfirm = viewModel::onCompactConfirmed,
        )
    }
    if (state.showRenameDialog) {
        RenameDialog(
            initial = state.deviceLabel,
            onCancel = viewModel::onRenameDialogDismissed,
            onConfirm = viewModel::onRenameDeviceConfirmed,
        )
    }
}

@Composable
private fun ConnectionSection(state: AnnotationSyncSettingsUiState, vm: AnnotationSyncSettingsViewModel) {
    SectionHeader(title = "Connection · WebDAV", sub = "Nextcloud, ownCloud, Synology, or any standard WebDAV host.")

    OutlinedTextField(
        value = state.baseUrl,
        onValueChange = vm::onBaseUrlChanged,
        label = { Text("WebDAV URL") },
        placeholder = { Text("https://server.example.com/dav/annotations") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )

    OutlinedTextField(
        value = state.username,
        onValueChange = vm::onUsernameChanged,
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.password,
        onValueChange = vm::onPasswordChanged,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = vm::onTestConnection,
            enabled = state.testResult !is TestConnectionUiState.Testing,
            modifier = Modifier.weight(1f),
        ) { Text(if (state.testResult is TestConnectionUiState.Testing) "Testing..." else "Test connection") }

        Button(
            onClick = vm::onSave,
            enabled = !state.saving && state.baseUrl.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text(if (state.saving) "Saving..." else "Save") }
    }

    OutlinedButton(
        onClick = vm::onClear,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Disable sync (clear config)") }

    TestConnectionStatus(state.testResult)
}

@Composable
private fun MaintenanceSection(state: AnnotationSyncSettingsUiState, vm: AnnotationSyncSettingsViewModel) {
    SectionHeader(
        title = "Maintenance",
        sub = "Manage device files on the sync server. Both actions are manual and run only when you tap them.",
    )

    when (val m = state.maintenance) {
        MaintenanceUiState.Hidden -> Unit
        MaintenanceUiState.NoNamespace -> Text(
            "Sign in to an Audiobookshelf server to see device files (the sync namespace is your ABS user id).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MaintenanceUiState.Loading -> Text(
            "Loading devices…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is MaintenanceUiState.Error -> Text(
            m.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is MaintenanceUiState.Loaded -> DeviceListCard(m.devices, vm)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        TextButton(onClick = vm::onRefreshMaintenance, enabled = !state.maintenanceBusy) {
            Text("Refresh")
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = vm::onCompactRequested,
            enabled = state.maintenance is MaintenanceUiState.Loaded && !state.maintenanceBusy,
        ) { Text("Compact tombstones…") }
    }

    MaintenanceSnackBanner(state.snack, vm::onSnackDismissed)
}

@Composable
private fun DeviceListCard(devices: List<DeviceRowUiState>, vm: AnnotationSyncSettingsViewModel) {
    if (devices.isEmpty()) {
        Text(
            "No device files found on the server yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            devices.forEachIndexed { index, row ->
                DeviceRow(row, vm)
                if (index != devices.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(row: DeviceRowUiState, vm: AnnotationSyncSettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (row.isThisDevice) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("This device", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            if (row.secondary.isNotBlank()) {
                Text(
                    row.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.isThisDevice) {
            IconButton(onClick = vm::onRenameDeviceRequested) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename this device")
            }
        } else {
            OutlinedButton(onClick = { vm.onForgetRequested(row) }) { Text("Forget") }
        }
    }
}

@Composable
private fun ForgetDialog(row: DeviceRowUiState, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Forget \"${row.label}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Removes this device's files from the WebDAV server. Other devices still have copies of " +
                        "these annotations in their own files, so the records are preserved.",
                )
                Text(
                    "Edge case: if this device went offline before any other device synced its last edits, " +
                        "those edits exist only on its files and will be lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Forget device") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CompactDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Compact tombstones?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Permanently removes deletion records from every device's files on the server, across " +
                        "every book.",
                )
                Text(
                    "Only safe when every device is online and fully synced.",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "If a device is offline holding a pre-deletion copy of an annotation, it will re-create " +
                        "that annotation on every device once it comes back online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Compact") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename this device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Shown on every device in the household. Saved on the WebDAV server in a small per-device " +
                        "file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun MaintenanceSnackBanner(snack: MaintenanceSnack, onDismiss: () -> Unit) {
    val (text, isError) = when (snack) {
        MaintenanceSnack.None -> return
        is MaintenanceSnack.Forgot -> {
            val parts = mutableListOf<String>()
            parts += "Forgot \"${snack.label}\""
            parts += "${snack.files} file(s) removed"
            if (snack.sidecarDeleted) parts += "sidecar removed"
            if (snack.failures > 0) parts += "${snack.failures} failure(s)"
            parts.joinToString(" · ") to (snack.failures > 0)
        }
        is MaintenanceSnack.Compacted -> {
            val parts = mutableListOf<String>()
            parts += "Compacted"
            parts += "${snack.rewritten} file(s) rewritten"
            parts += "${snack.removed} tombstone(s) removed"
            if (snack.failures > 0) parts += "${snack.failures} failure(s)"
            parts.joinToString(" · ") to (snack.failures > 0)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, sub: String?) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (!sub.isNullOrBlank()) {
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TestConnectionStatus(result: TestConnectionUiState) {
    val (text, color) = when (result) {
        TestConnectionUiState.Idle -> return
        TestConnectionUiState.Testing -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        TestConnectionUiState.Success -> "Connected. Base directory is ready." to Color(0xFF2E7D32)
        TestConnectionUiState.AuthFailed -> "Authentication failed — check your username and password." to MaterialTheme.colorScheme.error
        is TestConnectionUiState.InvalidUrl -> result.message to MaterialTheme.colorScheme.error
        is TestConnectionUiState.NetworkError -> "Couldn't reach the server: ${result.message}" to MaterialTheme.colorScheme.error
        is TestConnectionUiState.TlsError -> "TLS error: ${result.message}" to MaterialTheme.colorScheme.error
        is TestConnectionUiState.ServerError -> "Server returned HTTP ${result.code}." to MaterialTheme.colorScheme.error
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
}
