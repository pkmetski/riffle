package com.riffle.feature.reader

import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LookupUiStateTest {

    private val catalogEntry = LanguageCatalogEntry(
        languageTag = "en",
        displayName = "English",
        jsonlUrl = "https://example.com/en.jsonl",
        approximateSizeBytes = 1_000_000L,
        attributionHtml = "",
        licenseUrl = "",
    )

    @Test
    fun installedWithResultsProducesLoaded() {
        val entries = listOf(DictionaryEntry("cat", "noun", listOf("a small animal")))
        val result = resolveLookupUiState(
            packState = DictionaryPackState.INSTALLED,
            word = "cat",
            languageTag = "en",
            entries = entries,
            recentLookups = emptyList(),
        )
        assertIs<LookupUiState.Loaded>(result)
        assertEquals("cat", result.word)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun installedWithNoResultsProducesNoResults() {
        val result = resolveLookupUiState(
            packState = DictionaryPackState.INSTALLED,
            word = "xyzzy",
            languageTag = "en",
            entries = emptyList(),
            recentLookups = emptyList(),
        )
        assertIs<LookupUiState.NoResults>(result)
        assertEquals("xyzzy", result.word)
    }

    @Test
    fun downloadingProducesDownloading() {
        val result = resolveLookupUiState(
            packState = DictionaryPackState.DOWNLOADING,
            word = "cat",
            languageTag = "en",
            entries = emptyList(),
            recentLookups = emptyList(),
        )
        assertIs<LookupUiState.Downloading>(result)
    }

    @Test
    fun failedProducesDownloadFailed() {
        val result = resolveLookupUiState(
            packState = DictionaryPackState.FAILED,
            word = "cat",
            languageTag = "en",
            entries = emptyList(),
            recentLookups = emptyList(),
            catalogEntry = catalogEntry,
        )
        val failed = assertIs<LookupUiState.DownloadFailed>(result)
        assertEquals("en", failed.languageTag)
        assertEquals(catalogEntry, failed.entry)
    }

    @Test
    fun notInstalledProducesNoPackInstalled() {
        val result = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "cat",
            languageTag = "en",
            entries = emptyList(),
            recentLookups = emptyList(),
            catalogEntry = catalogEntry,
        )
        val noPack = assertIs<LookupUiState.NoPackInstalled>(result)
        assertEquals("en", noPack.languageTag)
        assertEquals(catalogEntry, noPack.entry)
    }

    @Test
    fun notInstalledWithNoCatalogEntryHasNullEntry() {
        val result = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "cat",
            languageTag = "unknown-lang",
            entries = emptyList(),
            recentLookups = emptyList(),
        )
        val noPack = assertIs<LookupUiState.NoPackInstalled>(result)
        assertEquals(null, noPack.entry)
    }
}
