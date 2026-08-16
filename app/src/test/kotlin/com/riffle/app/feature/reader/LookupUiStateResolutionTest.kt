package com.riffle.app.feature.reader

import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.PackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `FAILED propagates manifest packInfo into DownloadFailed`() {
        val packInfo = PackInfo(
            languageTag = "fr",
            packVersion = "2026-08-01",
            downloadUrl = "https://example.com/fr.db",
            sha256 = "abc123",
            sizeBytes = 12_000_000L,
            attributionHtml = "<a>Wiktionary</a>",
            licenseUrl = "https://cc.org",
        )
        val state = resolveLookupUiState(
            packState = DictionaryPackState.FAILED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = packInfo.sizeBytes,
            manifestPackInfo = packInfo,
        )
        assertTrue(state is LookupUiState.DownloadFailed)
        assertEquals(packInfo, (state as LookupUiState.DownloadFailed).packInfo)
    }

    @Test
    fun `NOT_INSTALLED propagates manifest packInfo into NoPackInstalled`() {
        val packInfo = PackInfo(
            languageTag = "fr",
            packVersion = "2026-08-01",
            downloadUrl = "https://example.com/fr.db",
            sha256 = "abc123",
            sizeBytes = 12_000_000L,
            attributionHtml = "<a>Wiktionary</a>",
            licenseUrl = "https://cc.org",
        )
        val state = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = packInfo.sizeBytes,
            manifestPackInfo = packInfo,
        )
        assertTrue(state is LookupUiState.NoPackInstalled)
        assertEquals(packInfo, (state as LookupUiState.NoPackInstalled).packInfo)
    }

    @Test
    fun `NOT_INSTALLED with null manifest packInfo produces null packInfo in state`() {
        val state = resolveLookupUiState(
            packState = DictionaryPackState.NOT_INSTALLED,
            word = "chat",
            languageTag = "fr",
            entries = emptyList(),
            recentLookups = emptyList(),
            manifestSizeBytes = 0L,
            manifestPackInfo = null,
        )
        assertTrue(state is LookupUiState.NoPackInstalled)
        assertNull((state as LookupUiState.NoPackInstalled).packInfo)
    }
}
