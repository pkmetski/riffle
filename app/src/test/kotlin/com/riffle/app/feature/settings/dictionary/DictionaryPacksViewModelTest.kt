package com.riffle.app.feature.settings.dictionary

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.riffle.app.dictionary.DictionaryPackScheduler
import com.riffle.core.data.dictionary.PackManifestFetcher
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.PackInfo
import com.riffle.core.dictionary.PackManifest
import com.riffle.core.dictionary.PackStore
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DictionaryPacksViewModelTest {

    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private val installedFrench = InstalledPack(
        languageTag = "fr",
        packVersion = "2026-08-01",
        installedAt = 0L,
        sizeBytes = 12_000_000L,
        attributionHtml = "<a>Wiktionary</a>",
        licenseUrl = "https://cc.org",
    )

    private val frPack = PackInfo(
        languageTag = "fr",
        packVersion = "2026-09-01",
        downloadUrl = "https://example.com/fr.db",
        sha256 = "abc123",
        sizeBytes = 13_000_000L,
        attributionHtml = "<a>Wiktionary</a>",
        licenseUrl = "https://cc.org",
    )

    @Test
    fun `manifest is loaded on init`() = runTest {
        val manifest = PackManifest(version = 1, packs = listOf(frPack))
        val vm = viewModel(manifest = manifest)
        advanceUntilIdle()
        assertEquals(manifest, vm.manifest.value)
        assertFalse(vm.manifestError.value)
    }

    @Test
    fun `manifestError is set when fetch fails`() = runTest {
        val vm = viewModel(manifestFails = true)
        advanceUntilIdle()
        assertTrue(vm.manifestError.value)
    }

    @Test
    fun `installedPacks reflects the PackStore`() = runTest {
        val vm = viewModel(installed = listOf(installedFrench))
        advanceUntilIdle()
        assertEquals(1, vm.installedPacks.value.size)
        assertEquals("fr", vm.installedPacks.value[0].languageTag)
    }

    // --- Helpers ---

    private fun viewModel(
        installed: List<InstalledPack> = emptyList(),
        manifest: PackManifest = PackManifest(version = 1, packs = emptyList()),
        manifestFails: Boolean = false,
    ): DictionaryPacksViewModel {
        val packStore = object : PackStore {
            override fun observeInstalledPacks(): Flow<List<InstalledPack>> = flowOf(installed)
            override fun observePackState(languageTag: String) = flowOf(DictionaryPackState.NOT_INSTALLED)
            override suspend fun deleteInstalledPack(languageTag: String) {}
        }
        val fetcher = object : PackManifestFetcher(
            httpClient = mockk(relaxed = true),
            manifestUrl = "",
        ) {
            override suspend fun fetch(): PackManifest {
                if (manifestFails) throw Exception("fetch failed")
                return manifest
            }
        }
        val scheduler = object : DictionaryPackScheduler() {
            override fun enqueueDownload(context: android.content.Context, packInfo: PackInfo) {}
        }
        return DictionaryPacksViewModel(packStore, fetcher, scheduler)
    }
}
