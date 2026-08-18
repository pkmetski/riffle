package com.riffle.app.feature.settings.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.LanguageCatalog
import com.riffle.core.dictionary.LanguageCatalogEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryPacksScreen(
    onNavigateBack: () -> Unit,
    viewModel: DictionaryPacksViewModel = hiltViewModel(),
) {
    val installedPacks by viewModel.installedPacks.collectAsState()
    val context = LocalContext.current

    val installedTags = installedPacks.map { it.languageTag }.toSet()
    val availablePacks = viewModel.catalog.filter { it.languageTag !in installedTags }

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
                    InstalledPackRow(
                        pack = pack,
                        onDelete = { viewModel.deleteInstalledPack(pack.languageTag) },
                        onUpdate = { viewModel.enqueueUpdate(context, pack.languageTag) },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Available",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (availablePacks.isEmpty()) {
                Text(
                    text = "All available packs are installed.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                availablePacks.forEach { entry ->
                    AvailablePackRow(
                        entry = entry,
                        onDownload = { viewModel.enqueueDownload(context, entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledPackRow(
    pack: InstalledPack,
    onDelete: () -> Unit,
    onUpdate: () -> Unit,
) {
    val displayName = LanguageCatalog.entryFor(pack.languageTag)?.displayName ?: pack.languageTag
    val attributionText = HtmlCompat.fromHtml(pack.attributionHtml, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "$displayName · ${pack.packVersion} · ${formatBytes(pack.sizeBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = attributionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (pack.licenseUrl.isNotBlank()) {
            Text(
                text = pack.licenseUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onUpdate) { Text("Update") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun AvailablePackRow(
    entry: LanguageCatalogEntry,
    onDownload: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(entry.displayName) },
        supportingContent = { Text("~${formatBytes(entry.approximateSizeBytes)}") },
        trailingContent = {
            TextButton(onClick = onDownload) { Text("Download") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.0f MB".format(mb) else "${bytes / 1024} KB"
}
