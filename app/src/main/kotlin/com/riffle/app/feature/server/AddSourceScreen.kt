package com.riffle.app.feature.server

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.ui.source.SourceTypeIcon
import com.riffle.core.domain.AddSourceCopy
import com.riffle.core.models.InsecureConnectionType
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.domain.WebSourceDescriptors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onAuthenticated: (PendingSource) -> Unit,
    onAutoCompleted: () -> Unit,
    viewModel: AddSourceViewModel = hiltViewModel(),
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
    // (ADR 0044 Phase 7). WebDAV isn't a browsable catalog — it's an annotation-sync sidecar
    // that happens to share the URL + username + password form shape — so it keeps a small
    // per-screen constant block below rather than a synthetic descriptor.
    val credentialed = backend as? AddSourceBackend.Credentialed
    val descriptor: WebSourceDescriptor? = credentialed?.let {
        WebSourceDescriptors.forType(it.sourceType)
    }
    val descriptorCopy: AddSourceCopy? = credentialed?.let {
        descriptor?.addSourceCopyFor(it.serverType)
    }
    val title = descriptorCopy?.let { if (isEditing) it.editTitle else it.addTitle }
        ?: if (isEditing) "Edit WebDAV" else "Add WebDAV"
    val urlLabel = descriptorCopy?.urlLabel ?: "WebDAV URL"
    val urlPlaceholder = descriptorCopy?.urlPlaceholder ?: "server.example.com/dav/annotations"
    val submitLabel = descriptorCopy
        ?.let { if (isEditing) it.submitLabelEdit else it.submitLabelAdd }
        ?: if (isEditing) "Save" else "Connect"

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val helpText = descriptorCopy?.helpText
                    ?: "Sync highlights, notes, and bookmarks between your devices via a WebDAV server."
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var schemeExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { schemeExpanded = true }) {
                            Text(viewModel.scheme)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose scheme")
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
                OutlinedTextField(
                    value = viewModel.username,
                    onValueChange = { viewModel.username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = viewModel.password,
                    onValueChange = { viewModel.password = it },
                    label = { Text("Password") },
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
                        enabled = viewModel.host.isNotBlank() && viewModel.username.isNotBlank() && viewModel.password.isNotBlank(),
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
                            Text(descriptorCopy?.removeLabel ?: "Disable sync")
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
            "Synced via WebDAV",
            Icons.Default.CheckCircle,
        )
        WebdavBannerKind.Pending -> Quadruple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Pending — will retry automatically",
            Icons.Default.Schedule,
        )
        WebdavBannerKind.Error -> Quadruple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Sync error",
            Icons.Default.Warning,
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
                "Last sync: ${banner.lastSyncRelative}",
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
        InsecureConnectionType.HTTP -> "Insecure connection"
        InsecureConnectionType.SELF_SIGNED -> "Untrusted certificate"
    }
    val body = when (type) {
        InsecureConnectionType.HTTP ->
            "This server uses HTTP. Your credentials will be sent without encryption. Proceed only if you trust this network."
        InsecureConnectionType.SELF_SIGNED ->
            "The server's TLS certificate cannot be verified. Connecting may expose your credentials. Proceed only if you trust this server."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Connect anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
