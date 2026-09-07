package com.riffle.feature.source.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AddSourceCopy
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.InsecureConnectionType
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_add_webdav
import com.riffle.feature.source.ui.generated.resources.ui_back
import com.riffle.feature.source.ui.generated.resources.ui_cancel
import com.riffle.feature.source.ui.generated.resources.ui_choose_scheme
import com.riffle.feature.source.ui.generated.resources.ui_connect
import com.riffle.feature.source.ui.generated.resources.ui_connect_anyway
import com.riffle.feature.source.ui.generated.resources.ui_disable_sync
import com.riffle.feature.source.ui.generated.resources.ui_edit_webdav
import com.riffle.feature.source.ui.generated.resources.ui_insecure_connection
import com.riffle.feature.source.ui.generated.resources.ui_insecure_http_description
import com.riffle.feature.source.ui.generated.resources.ui_last_sync_value
import com.riffle.feature.source.ui.generated.resources.ui_password
import com.riffle.feature.source.ui.generated.resources.ui_pending_will_retry_automatically
import com.riffle.feature.source.ui.generated.resources.ui_save
import com.riffle.feature.source.ui.generated.resources.ui_sync_error
import com.riffle.feature.source.ui.generated.resources.ui_synced_via_webdav
import com.riffle.feature.source.ui.generated.resources.ui_untrusted_certificate
import com.riffle.feature.source.ui.generated.resources.ui_untrusted_certificate_description
import com.riffle.feature.source.ui.generated.resources.ui_username
import com.riffle.feature.source.ui.generated.resources.ui_webdav_add_source_help_text
import com.riffle.feature.source.ui.generated.resources.ui_webdav_url
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceScreen(
    isExpandedWidth: Boolean,
    onNavigateBack: () -> Unit,
    onAuthenticated: (PendingSource) -> Unit,
    onAutoCompleted: () -> Unit,
    viewModel: AddSourceViewModel,
) {
    LaunchedEffect(Unit) {
        viewModel.navigateToSelectLibraries.collect { onAuthenticated(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.navigateHome.collect { onAutoCompleted() }
    }

    val backend = viewModel.backend
    val isEditing = viewModel.isEditing
    // Every credentialed catalog source drives its copy through its [WebSourceDescriptor]
    // (ADR 0053 Phase 7). WebDAV isn't a browsable catalog — it's an annotation-sync sidecar
    // that happens to share the URL + username + password form shape — so it keeps a small
    // per-screen constant block below rather than a synthetic descriptor.
    val credentialed = backend as? AddSourceBackend.Credentialed
    val descriptor: WebSourceDescriptor? = credentialed?.let {
        WebSourceDescriptors.forType(it.sourceType)
    }
    val descriptorCopy: AddSourceCopy? = credentialed?.let {
        descriptor?.let { d -> localizedAddSourceCopy(d, it.serverType) }
    }
    val title = descriptorCopy?.let { if (isEditing) it.editTitle else it.addTitle }
        ?: stringResource(if (isEditing) Res.string.ui_edit_webdav else Res.string.ui_add_webdav)
    val urlLabel = descriptorCopy?.urlLabel ?: stringResource(Res.string.ui_webdav_url)
    val urlPlaceholder = descriptorCopy?.urlPlaceholder ?: "server.example.com/dav/annotations"
    val submitLabel = descriptorCopy
        ?.let { if (isEditing) it.submitLabelEdit else it.submitLabelAdd }
        ?: stringResource(if (isEditing) Res.string.ui_save else Res.string.ui_connect)
    // Fixed-host credentialed sources (O'Reilly) have no user-entered URL: the ViewModel stamps
    // the host and the URL row is suppressed. WebDAV (descriptor == null) and every hasNetworkHost
    // source keep the row.
    val showUrlField = descriptor?.hasNetworkHost != false

    viewModel.insecureWarning?.let { type ->
        InsecureConnectionDialog(
            type = type,
            onConfirm = viewModel::onInsecureWarningAccepted,
            onDismiss = viewModel::onInsecureWarningDismissed,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        credentialed?.let {
                            SourceTypeIcon(
                                type = it.sourceType,
                                serverType = it.serverType,
                                size = 28.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(SourceUiIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            isExpandedWidth = isExpandedWidth,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val helpText = descriptorCopy?.helpText
                    ?: stringResource(Res.string.ui_webdav_add_source_help_text)
                if (helpText.isNotEmpty()) {
                    Text(
                        text = helpText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val webdavBanner by viewModel.webdavBanner.collectAsState()
                if (backend == AddSourceBackend.Webdav) {
                    webdavBanner?.let { WebdavStatusCard(it) }
                }
                if (showUrlField) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var schemeExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { schemeExpanded = true }) {
                                Text(viewModel.scheme)
                                Icon(SourceUiIcons.ArrowDropDown, contentDescription = stringResource(Res.string.ui_choose_scheme))
                            }
                            DropdownMenu(
                                expanded = schemeExpanded,
                                onDismissRequest = { schemeExpanded = false },
                            ) {
                                listOf("https://", "http://").forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            viewModel.updateScheme(option)
                                            schemeExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = viewModel.host,
                            onValueChange = { viewModel.updateHost(it) },
                            label = { Text(urlLabel) },
                            placeholder = { Text(urlPlaceholder) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                        )
                    }
                }
                OutlinedTextField(
                    value = viewModel.username,
                    onValueChange = { viewModel.username = it },
                    label = { Text(stringResource(Res.string.ui_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = viewModel.password,
                    onValueChange = { viewModel.password = it },
                    label = { Text(stringResource(Res.string.ui_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                viewModel.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (viewModel.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Button(
                        onClick = viewModel::onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = (!showUrlField || viewModel.host.isNotBlank()) && viewModel.username.isNotBlank() && viewModel.password.isNotBlank(),
                    ) {
                        Text(submitLabel)
                    }
                    if (isEditing) {
                        OutlinedButton(
                            onClick = viewModel::onRemove,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(
                                descriptorCopy?.removeLabel
                                    ?: stringResource(Res.string.ui_disable_sync),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebdavStatusCard(banner: WebdavBanner) {
    val (container, content, header, glyph) = when (banner.kind) {
        WebdavBannerKind.Synced -> Quadruple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(Res.string.ui_synced_via_webdav),
            SourceUiIcons.CheckCircle,
        )
        WebdavBannerKind.Pending -> Quadruple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(Res.string.ui_pending_will_retry_automatically),
            SourceUiIcons.Schedule,
        )
        WebdavBannerKind.Error -> Quadruple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(Res.string.ui_sync_error),
            SourceUiIcons.Warning,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(glyph, contentDescription = null, tint = content)
                Text(header, style = MaterialTheme.typography.titleMedium, color = content)
            }
            Text(
                "${banner.username}@${banner.host} · ${banner.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = content,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                stringResource(Res.string.ui_last_sync_value, banner.lastSyncRelative),
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
            banner.prescription?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
    }
}

/** Local quadruple to keep the when expression readable. */
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun InsecureConnectionDialog(
    type: InsecureConnectionType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (type) {
        InsecureConnectionType.HTTP -> stringResource(Res.string.ui_insecure_connection)
        InsecureConnectionType.SELF_SIGNED -> stringResource(Res.string.ui_untrusted_certificate)
    }
    val body = when (type) {
        InsecureConnectionType.HTTP -> stringResource(Res.string.ui_insecure_http_description)
        InsecureConnectionType.SELF_SIGNED -> stringResource(Res.string.ui_untrusted_certificate_description)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.ui_connect_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ui_cancel)) } },
    )
}
