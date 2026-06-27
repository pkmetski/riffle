package com.riffle.app.feature.settings.annotationsync

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationSyncMaintenanceScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnnotationSyncMaintenanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maintenance") },
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
                "Each device using this account writes its own annotation file per book to WebDAV. " +
                    "The list below shows every device whose files are currently on the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state.devices) {
                MaintenanceScreenUiState.NotConfigured -> StatusText(
                    "Annotation sync isn't set up yet. Configure a WebDAV server first to manage devices.",
                )
                MaintenanceScreenUiState.NoNamespace -> StatusText(
                    "Sign in to an Audiobookshelf server to see device files (the sync namespace is your ABS user id).",
                )
                MaintenanceScreenUiState.Loading -> StatusText("Loading devices…")
                is MaintenanceScreenUiState.Error -> Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is MaintenanceScreenUiState.Loaded -> DeviceListCard(s.devices, viewModel)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::onRefresh, enabled = !state.busy) { Text("Refresh") }
            }

            MaintenanceSnackBanner(state.snack, viewModel::onSnackDismissed)

            if (state.otherUsers.isNotEmpty()) {
                OtherUsersSection(state.otherUsers, viewModel)
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
    state.pendingForgetUser?.let { group ->
        ForgetUserDialog(
            group = group,
            onCancel = viewModel::onForgetUserCancelled,
            onConfirm = viewModel::onForgetUserConfirmed,
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
private fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeviceListCard(devices: List<MaintenanceDeviceRowUiState>, vm: AnnotationSyncMaintenanceViewModel) {
    if (devices.isEmpty()) {
        StatusText("No device files found on the server yet.")
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
private fun DeviceRow(row: MaintenanceDeviceRowUiState, vm: AnnotationSyncMaintenanceViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun ForgetDialog(row: MaintenanceDeviceRowUiState, onCancel: () -> Unit, onConfirm: () -> Unit) {
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
private fun RenameDialog(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename this device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Shown on every device in the household.",
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
private fun OtherUsersSection(
    groups: List<OtherUserGroupUiState>,
    vm: AnnotationSyncMaintenanceViewModel,
) {
    Text(
        "Other users on this share",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "Files written by a different Audiobookshelf account. They aren't read by any device " +
            "using your current account. Expand to forget individual devices, or remove the " +
            "whole user.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Expanded state lives at the section, keyed on namespace — survives recomposition but not
    // process death, which is fine (the screen always reopens collapsed).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            groups.forEachIndexed { index, group ->
                val isOpen = expanded[group.namespace] ?: false
                OtherUserGroup(
                    group = group,
                    isOpen = isOpen,
                    onToggle = { expanded[group.namespace] = !isOpen },
                    vm = vm,
                )
                if (index != groups.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun OtherUserGroup(
    group: OtherUserGroupUiState,
    isOpen: Boolean,
    onToggle: () -> Unit,
    vm: AnnotationSyncMaintenanceViewModel,
) {
    val caretRotation by animateFloatAsState(if (isOpen) 90f else 0f, label = "caret")
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = if (isOpen) "Collapse" else "Expand",
                modifier = Modifier.rotate(caretRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.displayLabel ?: "Unknown user",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val countLabel = "${group.devices.size} device" +
                    (if (group.devices.size == 1) "" else "s") +
                    " · " +
                    "${group.totalFileCount} file" + (if (group.totalFileCount == 1) "" else "s")
                val secondary = if (group.displayLabel == null) {
                    "${group.namespace.take(8)}… · $countLabel"
                } else {
                    countLabel
                }
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isOpen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    group.devices.forEachIndexed { idx, device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 38.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (device.secondary.isNotBlank()) {
                                    Text(
                                        device.secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedButton(onClick = { vm.onForgetRequested(device) }) {
                                Text("Forget")
                            }
                        }
                        if (idx != group.devices.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { vm.onForgetUserRequested(group) },
                        ) {
                            Text("Forget all from this user", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgetUserDialog(
    group: OtherUserGroupUiState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val label = group.displayLabel ?: "this user"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Forget all from \"$label\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append("Removes every file from this user on the WebDAV server — ")
                        append("${group.devices.size} device")
                        if (group.devices.size != 1) append("s")
                        append(", ${group.totalFileCount} file")
                        if (group.totalFileCount != 1) append("s")
                        append(".")
                    },
                )
                Text(
                    "Only do this if you're sure no device is still using this account — usually " +
                        "it's an old account you no longer sync from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Forget all") } },
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
            if (snack.failures > 0) parts += "${snack.failures} failure(s)"
            parts.joinToString(" · ") to (snack.failures > 0)
        }
        is MaintenanceSnack.Renamed -> {
            // The sentinel is a single file; reporting a count would always say "1 file(s) updated"
            // on success, which carries no information. Surface only success vs. failure.
            val text = if (snack.failures > 0) "Device rename failed" else "Device renamed"
            text to (snack.failures > 0)
        }
        is MaintenanceSnack.ForgotUser ->
            "Forgot \"${snack.userLabel}\" · ${snack.files} file(s) removed" to false
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
