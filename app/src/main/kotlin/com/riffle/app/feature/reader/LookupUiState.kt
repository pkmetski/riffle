package com.riffle.app.feature.reader

import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.PackInfo

data class LookupTarget(val text: String, val languageTag: String)

sealed interface LookupUiState {
    data object Loading : LookupUiState
    data class NoPackInstalled(val languageTag: String, val sizeBytes: Long, val packInfo: PackInfo? = null) : LookupUiState
    data object Downloading : LookupUiState
    data class DownloadFailed(val languageTag: String, val packInfo: PackInfo? = null) : LookupUiState
    data class Loaded(
        val word: String,
        val entries: List<DictionaryEntry>,
        val recentLookups: List<String>,
    ) : LookupUiState
    data class NoResults(val word: String, val languageTag: String) : LookupUiState
}

internal fun resolveLookupUiState(
    packState: DictionaryPackState,
    word: String,
    languageTag: String,
    entries: List<DictionaryEntry>,
    recentLookups: List<String>,
    manifestSizeBytes: Long,
    manifestPackInfo: PackInfo? = null,
): LookupUiState = when (packState) {
    DictionaryPackState.INSTALLED ->
        if (entries.isEmpty()) LookupUiState.NoResults(word, languageTag)
        else LookupUiState.Loaded(word, entries, recentLookups)
    DictionaryPackState.DOWNLOADING -> LookupUiState.Downloading
    DictionaryPackState.FAILED -> LookupUiState.DownloadFailed(languageTag, manifestPackInfo)
    DictionaryPackState.NOT_INSTALLED -> LookupUiState.NoPackInstalled(languageTag, manifestSizeBytes, manifestPackInfo)
}
