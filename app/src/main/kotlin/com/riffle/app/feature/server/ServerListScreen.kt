package com.riffle.app.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.domain.Server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onAddServer: () -> Unit,
    onBrowseLibrary: () -> Unit = {},
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Servers") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(Icons.Default.Add, contentDescription = "Add server")
            }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No servers added yet", style = MaterialTheme.typography.bodyLarge)
                Text("Tap + to connect to your Audiobookshelf server", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
                items(servers) { server ->
                    ServerItem(
                        server = server,
                        onSetActive = { viewModel.setActive(server) },
                        onRemove = { viewModel.remove(server) },
                        onBrowse = onBrowseLibrary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerItem(
    server: Server,
    onSetActive: () -> Unit,
    onRemove: () -> Unit,
    onBrowse: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (server.isActive)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.displayName, style = MaterialTheme.typography.titleMedium)
                Text(server.url.value, style = MaterialTheme.typography.bodySmall)
                if (server.isActive) {
                    Text("Active", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!server.isActive) {
                TextButton(onClick = onSetActive) { Text("Use") }
            }
            if (server.isActive) {
                TextButton(onClick = onBrowse) { Text("Browse") }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove server")
            }
        }
    }
}
