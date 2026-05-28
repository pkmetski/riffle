package com.riffle.app.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.PendingServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onNavigateBack: () -> Unit,
    onAuthenticated: (PendingServer) -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.navigateToSelectLibraries.collect { onAuthenticated(it) }
    }

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
                title = { Text("Add Server") },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = viewModel.url,
                onValueChange = { viewModel.url = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://abs.example.com") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
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
                    enabled = viewModel.url.isNotBlank() && viewModel.username.isNotBlank() && viewModel.password.isNotBlank(),
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

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
