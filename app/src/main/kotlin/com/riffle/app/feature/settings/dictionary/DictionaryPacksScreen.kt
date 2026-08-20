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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.R
import com.riffle.app.feature.library.DownloadProgressIndicator
import com.riffle.app.feature.library.DownloadState
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
    val downloadStates by viewModel.downloadStates.collectAsState()

    val installedTags = installedPacks.map { it.languageTag }.toSet()
    val availablePacks = viewModel.catalog.filter { it.languageTag !in installedTags }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_dictionary_packs)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back))
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
                    text = stringResource(R.string.ui_installed),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                installedPacks.forEach { pack ->
                    InstalledPackRow(
                        pack = pack,
                        onDelete = { viewModel.deleteInstalledPack(pack.languageTag) },
                        onUpdate = { viewModel.enqueueUpdate(pack.languageTag) },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.ui_available),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (availablePacks.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_all_packs_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                availablePacks.forEach { entry ->
                    AvailablePackRow(
                        entry = entry,
                        downloadState = downloadStates[DictionaryPacksViewModel.downloadKey(entry.languageTag)],
                        onDownload = { viewModel.enqueueDownload(entry) },
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
            TextButton(onClick = onUpdate) { Text(stringResource(R.string.ui_update)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.ui_delete)) }
        }
    }
}

@Composable
private fun AvailablePackRow(
    entry: LanguageCatalogEntry,
    downloadState: DownloadState?,
    onDownload: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(entry.displayName) },
        supportingContent = { Text(stringResource(R.string.ui_approx_size, formatBytes(entry.approximateSizeBytes))) },
        trailingContent = {
            if (downloadState is DownloadState.InProgress) {
                DownloadProgressIndicator(
                    percent = downloadState.percent,
                    size = 36.dp,
                    label = "Downloading ${entry.displayName}",
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.ui_download)) }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.0f MB".format(mb) else "${bytes / 1024} KB"
}
