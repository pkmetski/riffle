package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import com.riffle.core.dictionary.LanguageCatalogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Composable
internal fun WordLookupSheet(
    target: LookupTarget,
    resultFlow: Flow<LookupUiState>,
    onDismiss: () -> Unit,
    onEnqueueDownload: (LanguageCatalogEntry) -> Unit,
) {
    com.riffle.feature.reader.ui.WordLookupSheet(
        target = com.riffle.feature.reader.LookupTarget(target.text, target.languageTag),
        resultFlow = resultFlow.map { it.toShared() },
        onDismiss = onDismiss,
        onEnqueueDownload = onEnqueueDownload,
        onSystemDictionary = null,
    )
}
