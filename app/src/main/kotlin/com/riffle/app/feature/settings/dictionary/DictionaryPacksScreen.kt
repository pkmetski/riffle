package com.riffle.app.feature.settings.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryPacksScreen(
    onNavigateBack: () -> Unit,
    viewModel: DictionaryPacksViewModel = hiltViewModel(),
) {
    val installedPacks by viewModel.installedPacks.collectAsState()
    val manifest by viewModel.manifest.collectAsState()
    val manifestError by viewModel.manifestError.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictionary packs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues),
        ) {
            if (installedPacks.isNotEmpty()) {
                Text(
                    text = "Installed",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                installedPacks.forEach { pack ->
                    InstalledPackRow(pack)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Available",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                manifestError -> {
                    Text(
                        text = "Could not load available packs. Check your connection.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    TextButton(
                        onClick = viewModel::refreshManifest,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("Retry")
                    }
                }
                manifest == null -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
                else -> {
                    val availablePacks = manifest!!.packs.filter { remote ->
                        installedPacks.none { it.languageTag == remote.languageTag }
                    }
                    if (availablePacks.isEmpty()) {
                        Text(
                            text = "All available packs are installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        availablePacks.forEach { pack ->
                            AvailablePackRow(
                                pack = pack,
                                onDownload = { viewModel.enqueueDownload(context, pack) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledPackRow(pack: InstalledPack) {
    ListItem(
        headlineContent = { Text(pack.languageTag) },
        supportingContent = { Text("v${pack.packVersion} · ${formatBytes(pack.sizeBytes)}") },
    )
}

@Composable
private fun AvailablePackRow(
    pack: PackInfo,
    onDownload: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(pack.languageTag) },
        supportingContent = { Text(formatBytes(pack.sizeBytes)) },
        trailingContent = {
            TextButton(onClick = onDownload) { Text("Download") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
