package com.riffle.app.feature.downloads

import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.StoredItemRef
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun item(sourceId: String, id: String, title: String) = LibraryItem(
        id = id,
        libraryId = "lib-$sourceId",
        title = title,
        author = "author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        sourceId = sourceId,
    )

    /** Regression: PR fix ungating Cached and Readaloud sections from the active source.
     *
     *  Prior behavior threaded the active Catalog's DownloadsCapability / ReadaloudCapability into
     *  showCachedSection / showReadaloudSection flags. When the active library was LocalFiles (which
     *  omits both capabilities), the ViewModel silently produced state that told the UI to hide
     *  every ABS/Chitanka/Storyteller Cached row and every Storyteller readaloud sidecar — even
     *  though the Downloaded list was rendered app-wide. The fix removes the capability lookup so
     *  all three lists behave the same way (populated whenever local artifacts exist).
     *
     *  This test would fail if someone re-adds capability gating: injecting a CatalogRegistry (or any
     *  active-source predicate) that drops rows from non-matching sources would make the cached and
     *  sidecar lists empty here. */
    @Test
    fun `populates all sections regardless of active source`() = runTest(dispatcher) {
        val downloadsRepo = mockk<DownloadsRepository>(relaxed = true)
        every { downloadsRepo.getDownloadedItems() } returns listOf(
            StoredItemRef("abs", "abs-book"),
            StoredItemRef("chitanka", "ch-book"),
        )
        every { downloadsRepo.getCachedItems() } returns listOf(
            StoredItemRef("abs", "abs-cached"),
        )
        every { downloadsRepo.sizeOf(any(), any()) } returns 1024L

        val libraryObserver = mockk<LibraryObserver>()
        coEvery { libraryObserver.getItem("abs", "abs-book") } returns item("abs", "abs-book", "ABS Downloaded")
        coEvery { libraryObserver.getItem("chitanka", "ch-book") } returns item("chitanka", "ch-book", "Chitanka Downloaded")
        coEvery { libraryObserver.getItem("abs", "abs-cached") } returns item("abs", "abs-cached", "ABS Cached")
        coEvery { libraryObserver.getItem("storyteller", "st-book") } returns item("storyteller", "st-book", "Storyteller Readaloud")

        val sidecarStore = mockk<ReadaloudSidecarStore>(relaxed = true)
        every { sidecarStore.listCached() } returns listOf(
            ReadaloudSidecarStore.CachedSidecar("storyteller", "st-book", 2048L),
        )

        val vm = DownloadsViewModel(downloadsRepo, libraryObserver, sidecarStore)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            listOf("ABS Downloaded", "Chitanka Downloaded"),
            state.downloadedItems.map { it.item.title },
        )
        assertEquals(listOf("ABS Cached"), state.cachedItems.map { it.item.title })
        assertEquals(listOf("Storyteller Readaloud"), state.readaloudSidecars.map { it.item.title })
    }
}
