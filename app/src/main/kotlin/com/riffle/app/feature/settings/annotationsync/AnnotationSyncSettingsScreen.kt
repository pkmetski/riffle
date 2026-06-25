package com.riffle.app.feature.settings.annotationsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    val formState by viewModel.state.collectAsState()
    val screenState by viewModel.screenState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.closeRequests.collect { onNavigateBack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotation Sync · WebDAV") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Sync highlights, notes, and bookmarks between your devices via a WebDAV server.",
                style = MaterialTheme.typography.bodySmall,
            )

            when (val s = screenState) {
                is AnnotationSyncScreenState.Unconfigured -> LocalOnlyCard()
                is AnnotationSyncScreenState.Configured -> ConfiguredStatusCard(s)
            }

            HorizontalDivider()

            // Form (always visible — edit in place is allowed for both states)
            OutlinedTextField(
                value = formState.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                label = { Text("WebDAV URL") },
                placeholder = { Text("https://server.example.com/dav/annotations") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = formState.username,
                onValueChange = viewModel::onUsernameChanged,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = formState.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::onTestConnection,
                    enabled = formState.testResult !is TestConnectionUiState.Testing,
                    modifier = Modifier.weight(1f),
                ) { Text(if (formState.testResult is TestConnectionUiState.Testing) "Testing..." else "Test connection") }
                Button(
                    onClick = viewModel::onSave,
                    enabled = !formState.saving && formState.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (formState.saving) "Saving..." else "Save") }
            }

            OutlinedButton(
                onClick = viewModel::onClear,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disable sync (clear config)") }

            TestConnectionStatus(formState.testResult)
        }
    }
}

@Composable
private fun LocalOnlyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("Local only", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(
                "Highlights, notes, and bookmarks are stored on this device only and will not " +
                    "sync to other devices. Configure WebDAV below to enable sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ConfiguredStatusCard(state: AnnotationSyncScreenState.Configured) {
    val (containerColor, contentColor, header, glyph) = when (val b = state.status) {
        AnnotationSyncScreenState.StatusBadge.Synced -> Quadruple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Synced via WebDAV",
            Icons.Default.CheckCircle,
        )
        is AnnotationSyncScreenState.StatusBadge.Pending -> Quadruple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            if (b.count == 0) "Pending — will retry automatically"
            else "Pending — ${b.count} book(s) unsynced",
            Icons.Default.Schedule,
        )
        is AnnotationSyncScreenState.StatusBadge.Error -> Quadruple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Sync error",
            Icons.Default.Warning,
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(glyph, contentDescription = null, tint = contentColor)
                Text(header, style = MaterialTheme.typography.titleMedium, color = contentColor)
            }
            Text(
                "${state.username}@${shortHost(state.baseUrl)} · ${state.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                fontFamily = FontFamily.Monospace,
            )
            Text("Last sync: ${state.lastSyncRelative}", style = MaterialTheme.typography.bodySmall, color = contentColor)
            errorSubLine(state.status)?.let { sub ->
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = contentColor)
            }
        }
    }
}

private fun errorSubLine(badge: AnnotationSyncScreenState.StatusBadge): String? = when (badge) {
    AnnotationSyncScreenState.StatusBadge.Error.Auth ->
        "Authentication failed — your credentials may have expired. Re-enter them below; sync will retry automatically once they're saved."
    AnnotationSyncScreenState.StatusBadge.Error.Tls ->
        "TLS error — the server's certificate could not be verified. Update the URL below; sync will retry automatically once saved."
    is AnnotationSyncScreenState.StatusBadge.Error.Server ->
        "Server returned HTTP ${badge.code}. Will retry automatically."
    AnnotationSyncScreenState.StatusBadge.Error.Unknown ->
        "Sync failed. Will retry automatically."
    is AnnotationSyncScreenState.StatusBadge.Pending ->
        "Couldn't reach the server. Will retry automatically when connectivity returns."
    AnnotationSyncScreenState.StatusBadge.Synced -> null
}

/** Tiny local quadruple to keep the when expression readable. */
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Strip scheme/path for compact identity display. */
private fun shortHost(rawUrl: String): String =
    runCatching { java.net.URI(rawUrl).host ?: rawUrl }.getOrElse { rawUrl }

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
