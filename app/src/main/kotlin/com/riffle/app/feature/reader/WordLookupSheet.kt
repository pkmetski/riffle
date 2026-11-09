package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val sharedTarget = remember(target) { com.riffle.feature.reader.LookupTarget(target.text, target.languageTag) }
    val sharedResultFlow = remember(resultFlow) { resultFlow.map { it.toShared() } }
    com.riffle.feature.reader.ui.WordLookupSheet(
        target = sharedTarget,
        resultFlow = sharedResultFlow,
        onDismiss = onDismiss,
        onEnqueueDownload = onEnqueueDownload,
        onSystemDictionary = null,
    )
}
