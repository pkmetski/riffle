package com.riffle.app.feature.reader

import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupUiStateResolutionTest {

    @Test
    fun `INSTALLED with results produces Loaded`() {
        val entries = listOf(DictionaryEntry("chat", "noun", listOf("a cat")))
        val state = resolveLookupUiState(
            packState = DictionaryPackState.INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = entries,
            recentLookups = emptyList(),
            manifestSizeBytes = 0L,
        )
        assertTrue(state is LookupUiState.Loaded)
        assertEquals("chat", (state as LookupUiState.Loaded).word)
    }

    @Test
    fun `INSTALLED with no results produces NoResults`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.INSTALLED,
            word = "zzz",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = 0L,
        )
        assertTrue(state is LookupUiState.NoResults)
    }

    @Test
    fun `NOT_INSTALLED produces NoPackInstalled`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = 12_000_000L,
        )
        assertTrue(state is LookupUiState.NoPackInstalled)
        assertEquals(12_000_000L, (state as LookupUiState.NoPackInstalled).sizeBytes)
    }

    @Test
    fun `DOWNLOADING produces Downloading`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.DOWNLOADING,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = 0L,
        )
        assertEquals(LookupUiState.Downloading, state)
    }

    @Test
    fun `FAILED produces DownloadFailed`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.FAILED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = 0L,
        )
        assertTrue(state is LookupUiState.DownloadFailed)
    }
}
