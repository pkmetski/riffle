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
                title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_maintenance)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
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
            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_annotations_webdav_maintenance_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state.devices) {
                MaintenanceScreenUiState.NotConfigured -> StatusText(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_annotation_sync_isn_t_set_up_yet_configure_a_webdav_server_first_to_manage_devic),
                )
                MaintenanceScreenUiState.NoNamespace -> StatusText(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_sign_in_to_an_audiobookshelf_server_to_see_device_files_the_sync_namespace_is_yo),
                )
                MaintenanceScreenUiState.Loading -> StatusText(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading_devices))
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
                TextButton(onClick = viewModel::onRefresh, enabled = !state.busy) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_refresh)) }
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
        StatusText(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_device_files_found_on_the_server_yet))
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
                        label = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_this_device), style = MaterialTheme.typography.labelSmall) },
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
                Icon(Icons.Filled.Edit, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_rename_this_device))
            }
        } else {
            OutlinedButton(onClick = { vm.onForgetRequested(row) }) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget)) }
        }
    }
}

@Composable
private fun ForgetDialog(row: MaintenanceDeviceRowUiState, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_device_title, row.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_device_explanation))
                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_device_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_device)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel)) } },
    )
}

@Composable
private fun RenameDialog(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_rename_this_device)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_shown_on_every_device_in_the_household),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel)) } },
    )
}

@Composable
private fun OtherUsersSection(
    groups: List<OtherUserGroupUiState>,
    vm: AnnotationSyncMaintenanceViewModel,
) {
    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_other_users_on_this_share),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_other_users_description),
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
                contentDescription = if (isOpen) {
                    androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_collapse)
                } else {
                    androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_expand)
                },
                modifier = Modifier.rotate(caretRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.displayLabel ?: androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_unknown_user),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val countLabel = androidx.compose.ui.res.stringResource(
                    com.riffle.app.R.string.ui_device_file_count,
                    group.devices.size,
                    group.totalFileCount,
                )
                val secondary = if (group.displayLabel == null) {
                    androidx.compose.ui.res.stringResource(
                        com.riffle.app.R.string.ui_unknown_user_secondary,
                        group.namespace.take(8),
                        countLabel,
                    )
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
                                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget))
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
                            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_all_from_this_user), color = MaterialTheme.colorScheme.error)
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
    val label = group.displayLabel ?: androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_this_user)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_all_from_user_title, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        com.riffle.app.R.string.ui_forget_user_summary,
                        group.devices.size,
                        group.totalFileCount,
                    ),
                )
                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_user_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forget_all)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel)) } },
    )
}

@Composable
private fun MaintenanceSnackBanner(snack: MaintenanceSnack, onDismiss: () -> Unit) {
    val (text, isError) = when (snack) {
        MaintenanceSnack.None -> return
        is MaintenanceSnack.Forgot -> {
            val parts = mutableListOf<String>()
            parts += androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_forgot_device_snack, snack.label)
            parts += androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_files_removed_count, snack.files)
            if (snack.failures > 0) {
                parts += androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_failures_count, snack.failures)
            }
            parts.joinToString(" · ") to (snack.failures > 0)
        }
        is MaintenanceSnack.Renamed -> {
            // The sentinel is a single file; reporting a count would always say "1 file(s) updated"
            // on success, which carries no information. Surface only success vs. failure.
            val text = if (snack.failures > 0) {
                androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_device_rename_failed)
            } else {
                androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_device_renamed)
            }
            text to (snack.failures > 0)
        }
        is MaintenanceSnack.ForgotUser ->
            androidx.compose.ui.res.stringResource(
                com.riffle.app.R.string.ui_forgot_user_snack,
                snack.userLabel,
                snack.files,
            ) to false
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
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_dismiss)) }
        }
    }
}
