package com.riffle.app.feature.reader

import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalog
import com.riffle.core.dictionary.LanguageCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupUiStateResolutionTest {

    private val frEntry = LanguageCatalog.entryFor("fr")!!

    @Test
    fun `INSTALLED with results produces Loaded`() {
        val entries = listOf(DictionaryEntry("chat", "noun", listOf("a cat")))
        val state = resolveLookupUiState(
            packState = DictionaryPackState.INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = entries,
            recentLookups = emptyList(),
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
        )
        assertTrue(state is LookupUiState.NoResults)
    }

    @Test
    fun `NOT_INSTALLED with null entry produces NoPackInstalled with null entry`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            catalogEntry = null,
        )
        assertTrue(state is LookupUiState.NoPackInstalled)
        assertNull((state as LookupUiState.NoPackInstalled).entry)
    }

    @Test
    fun `NOT_INSTALLED with catalog entry propagates entry into NoPackInstalled`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            catalogEntry = frEntry,
        )
        assertTrue(state is LookupUiState.NoPackInstalled)
        assertEquals(frEntry, (state as LookupUiState.NoPackInstalled).entry)
    }

    @Test
    fun `DOWNLOADING produces Downloading`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.DOWNLOADING,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
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
        )
        assertTrue(state is LookupUiState.DownloadFailed)
    }

    @Test
    fun `FAILED propagates catalog entry into DownloadFailed`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.FAILED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            catalogEntry = frEntry,
        )
        assertTrue(state is LookupUiState.DownloadFailed)
        assertEquals(frEntry, (state as LookupUiState.DownloadFailed).entry)
    }
}
