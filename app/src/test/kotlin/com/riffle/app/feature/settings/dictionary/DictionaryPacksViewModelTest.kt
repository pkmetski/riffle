package com.riffle.app.feature.settings.dictionary

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.riffle.app.feature.library.DownloadManager
import com.riffle.app.feature.library.DownloadState
import com.riffle.core.data.dictionary.PackDownloader
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.InstalledPack
import com.riffle.core.dictionary.LanguageCatalog
import com.riffle.core.dictionary.LanguageCatalogEntry
import com.riffle.core.dictionary.PackStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.atomic.AtomicInteger

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
    fun `enqueueDownload starts download via DownloadManager`() = runTest {
        val downloader = mockDownloader(returns = true)
        val vm = viewModel(downloader = downloader)
        val frEntry = LanguageCatalog.entryFor("fr")!!
        vm.enqueueDownload(frEntry)
        advanceUntilIdle()
        assertEquals(DownloadState.Downloaded, vm.downloadStates.value[DictionaryPacksViewModel.downloadKey("fr")])
    }

    @Test
    fun `enqueueDownload sets InProgress state while downloading`() = runTest {
        val downloader = mockDownloader(returns = true)
        val vm = viewModel(downloader = downloader)
        val frEntry = LanguageCatalog.entryFor("fr")!!
        vm.enqueueDownload(frEntry)
        // State is InProgress immediately after start() before the coroutine runs
        assertTrue(vm.downloadStates.value[DictionaryPacksViewModel.downloadKey("fr")] is DownloadState.InProgress)
    }

    @Test
    fun `enqueueUpdate delegates for known language`() = runTest {
        val callCount = AtomicInteger(0)
        val downloader = mockk<PackDownloader>()
        coEvery { downloader.download(any(), any()) } coAnswers { callCount.incrementAndGet(); true }
        val vm = viewModel(downloader = downloader)
        vm.enqueueUpdate("fr")
        advanceUntilIdle()
        assertEquals(1, callCount.get())
    }

    @Test
    fun `enqueueUpdate is no-op for unknown language`() = runTest {
        val callCount = AtomicInteger(0)
        val downloader = mockk<PackDownloader>()
        coEvery { downloader.download(any(), any()) } coAnswers { callCount.incrementAndGet(); true }
        val vm = viewModel(downloader = downloader)
        vm.enqueueUpdate("xx")
        advanceUntilIdle()
        assertEquals(0, callCount.get())
    }

    @Test
    fun `download failure sets NotDownloaded state`() = runTest {
        val downloader = mockDownloader(returns = false)
        val vm = viewModel(downloader = downloader)
        val frEntry = LanguageCatalog.entryFor("fr")!!
        vm.enqueueDownload(frEntry)
        advanceUntilIdle()
        assertEquals(DownloadState.NotDownloaded, vm.downloadStates.value[DictionaryPacksViewModel.downloadKey("fr")])
    }

    @Test
    fun `duplicate enqueueDownload while in progress is a no-op`() = runTest {
        val callCount = AtomicInteger(0)
        val downloader = mockk<PackDownloader>()
        coEvery { downloader.download(any(), any()) } coAnswers { callCount.incrementAndGet(); true }
        val vm = viewModel(downloader = downloader)
        val frEntry = LanguageCatalog.entryFor("fr")!!
        vm.enqueueDownload(frEntry)
        vm.enqueueDownload(frEntry) // second tap while in progress
        advanceUntilIdle()
        assertEquals(1, callCount.get())
    }

    // --- Helpers ---

    private fun mockDownloader(returns: Boolean): PackDownloader {
        val downloader = mockk<PackDownloader>()
        coEvery { downloader.download(any(), any()) } returns returns
        return downloader
    }

    private fun viewModel(
        installed: List<InstalledPack> = emptyList(),
        downloader: PackDownloader = mockDownloader(returns = true),
    ): DictionaryPacksViewModel {
        val packStore = object : PackStore {
            override fun observeInstalledPacks(): Flow<List<InstalledPack>> = flowOf(installed)
            override fun observePackState(languageTag: String) = flowOf(DictionaryPackState.NOT_INSTALLED)
            override suspend fun deleteInstalledPack(languageTag: String) {}
        }
        val downloadManager = DownloadManager(CoroutineScope(testDispatcher))
        return DictionaryPacksViewModel(packStore, downloader, downloadManager)
    }
}
