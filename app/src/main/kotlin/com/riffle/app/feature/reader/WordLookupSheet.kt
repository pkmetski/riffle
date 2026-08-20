package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.LanguageCatalogEntry
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WordLookupSheet(
    target: LookupTarget,
    resultFlow: Flow<LookupUiState>,
    onDismiss: () -> Unit,
    onEnqueueDownload: (LanguageCatalogEntry) -> Unit,
) {
    val result by resultFlow.collectAsState(initial = LookupUiState.Loading)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = target.text,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            when (val state = result) {
                LookupUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                is LookupUiState.NoPackInstalled -> {
                    Text(
                        text = stringResource(R.string.ui_no_dictionary_installed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val entry = state.entry
                    Text(
                        text = if (entry != null) {
                            stringResource(
                                R.string.ui_download_dictionary_pack_with_size,
                                state.languageTag,
                                formatBytes(entry.approximateSizeBytes),
                            )
                        } else {
                            stringResource(R.string.ui_download_dictionary_pack, state.languageTag)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                onEnqueueDownload(entry)
                                onDismiss()
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.ui_download))
                        }
                    }
                }
                LookupUiState.Downloading -> {
                    Text(
                        text = stringResource(R.string.ui_downloading_dictionary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is LookupUiState.DownloadFailed -> {
                    Text(
                        text = stringResource(R.string.ui_download_failed_retry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    val entry = state.entry
                    if (entry != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                onEnqueueDownload(entry)
                                onDismiss()
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.ui_retry))
                        }
                    }
                }
                is LookupUiState.NoResults -> {
                    Text(
                        text = stringResource(R.string.ui_no_results_for, state.word),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is LookupUiState.Loaded -> {
                    state.entries.forEach { entry ->
                        DictionaryEntryCard(entry)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DictionaryEntryCard(entry: DictionaryEntry) {
    Column {
        Text(
            text = entry.form,
            style = MaterialTheme.typography.titleMedium,
        )
        if (entry.partOfSpeech.isNotBlank()) {
            Text(
                text = entry.partOfSpeech,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        entry.glosses.forEachIndexed { index, definition ->
            Text(
                text = "${index + 1}. $definition",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
