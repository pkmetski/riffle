package com.riffle.app.feature.reader

import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalogEntry

data class LookupTarget(val text: String, val languageTag: String)

sealed interface LookupUiState {
    data object Loading : LookupUiState
    data class NoPackInstalled(
        val languageTag: String,
        val entry: LanguageCatalogEntry? = null,
    ) : LookupUiState
    data object Downloading : LookupUiState
    data class DownloadFailed(
        val languageTag: String,
        val entry: LanguageCatalogEntry? = null,
    ) : LookupUiState
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
    catalogEntry: LanguageCatalogEntry? = null,
): LookupUiState = when (packState) {
    DictionaryPackState.INSTALLED ->
        if (entries.isEmpty()) LookupUiState.NoResults(word, languageTag)
        else LookupUiState.Loaded(word, entries, recentLookups)
    DictionaryPackState.DOWNLOADING -> LookupUiState.Downloading
    DictionaryPackState.FAILED -> LookupUiState.DownloadFailed(languageTag, catalogEntry)
    DictionaryPackState.NOT_INSTALLED -> LookupUiState.NoPackInstalled(languageTag, catalogEntry)
}

internal fun LookupUiState.toShared(): com.riffle.feature.reader.LookupUiState = when (this) {
    LookupUiState.Loading -> com.riffle.feature.reader.LookupUiState.Loading
    is LookupUiState.NoPackInstalled -> com.riffle.feature.reader.LookupUiState.NoPackInstalled(languageTag, entry)
    LookupUiState.Downloading -> com.riffle.feature.reader.LookupUiState.Downloading
    is LookupUiState.DownloadFailed -> com.riffle.feature.reader.LookupUiState.DownloadFailed(languageTag, entry)
    is LookupUiState.Loaded -> com.riffle.feature.reader.LookupUiState.Loaded(word, entries, recentLookups)
    is LookupUiState.NoResults -> com.riffle.feature.reader.LookupUiState.NoResults(word, languageTag)
}
