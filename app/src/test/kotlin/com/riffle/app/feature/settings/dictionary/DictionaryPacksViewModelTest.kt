package com.riffle.app.feature.settings.dictionary

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.riffle.app.dictionary.DictionaryPackScheduler
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.LanguageCatalog
import com.riffle.core.dictionary.LanguageCatalogEntry
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
        packVersion = "2026-08-18",
        installedAt = 0L,
        sizeBytes = 120_000_000L,
        attributionHtml = "<a>Wiktionary</a>",
        licenseUrl = "https://cc.org",
    )

    @Test
    fun `catalog returns all LanguageCatalog entries`() {
        val vm = viewModel()
        assertEquals(LanguageCatalog.all, vm.catalog)
    }

    @Test
    fun `installedPacks reflects PackStore`() = runTest {
        val vm = viewModel(installed = listOf(installedFrench))
        advanceUntilIdle()
        assertEquals(1, vm.installedPacks.value.size)
        assertEquals("fr", vm.installedPacks.value[0].languageTag)
    }

    @Test
    fun `enqueueDownload delegates to scheduler`() {
        val scheduled = mutableListOf<LanguageCatalogEntry>()
        val vm = viewModel(onSchedule = { scheduled.add(it) })
        val frEntry = LanguageCatalog.entryFor("fr")!!
        vm.enqueueDownload(mockContext(), frEntry)
        assertEquals(listOf(frEntry), scheduled)
    }

    @Test
    fun `enqueueUpdate delegates to scheduler for known language`() {
        val scheduled = mutableListOf<LanguageCatalogEntry>()
        val vm = viewModel(onSchedule = { scheduled.add(it) })
        vm.enqueueUpdate(mockContext(), "fr")
        assertEquals(1, scheduled.size)
        assertEquals("fr", scheduled[0].languageTag)
    }

    @Test
    fun `enqueueUpdate is no-op for unknown language`() {
        val scheduled = mutableListOf<LanguageCatalogEntry>()
        val vm = viewModel(onSchedule = { scheduled.add(it) })
        vm.enqueueUpdate(mockContext(), "xx")
        assertTrue(scheduled.isEmpty())
    }

    // --- Helpers ---

    private fun viewModel(
        installed: List<InstalledPack> = emptyList(),
        onSchedule: (LanguageCatalogEntry) -> Unit = {},
    ): DictionaryPacksViewModel {
        val packStore = object : PackStore {
            override fun observeInstalledPacks(): Flow<List<InstalledPack>> = flowOf(installed)
            override fun observePackState(languageTag: String) = flowOf(DictionaryPackState.NOT_INSTALLED)
            override suspend fun deleteInstalledPack(languageTag: String) {}
        }
        val scheduler = object : DictionaryPackScheduler() {
            override fun enqueueDownload(context: Context, entry: LanguageCatalogEntry) {
                onSchedule(entry)
            }
        }
        return DictionaryPacksViewModel(packStore, scheduler)
    }

    private fun mockContext(): Context = mockk(relaxed = true)
}
