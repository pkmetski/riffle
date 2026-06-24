package com.riffle.app.feature.settings.annotationsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                text = "Sync highlights, notes, and bookmarks between your devices via a WebDAV server " +
                    "(Nextcloud, ownCloud, Synology, or any standard WebDAV host).",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                label = { Text("WebDAV URL") },
                placeholder = { Text("https://server.example.com/dav/annotations") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChanged,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::onTestConnection,
                    enabled = state.testResult !is TestConnectionUiState.Testing,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.testResult is TestConnectionUiState.Testing) "Testing..." else "Test connection") }

                Button(
                    onClick = viewModel::onSave,
                    enabled = !state.saving && state.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.saving) "Saving..." else "Save") }
            }

            OutlinedButton(
                onClick = viewModel::onClear,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disable sync (clear config)") }

            TestConnectionStatus(state.testResult)
        }
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
