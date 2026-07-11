package com.riffle.app.feature.source.chitanka

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.data.chitanka.ChitankaLibraryItemUpserter
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins openItem: upserts the tapped [CatalogItem] into `library_items` (so the reader / player can
 * resolve it) and emits an [ChitankaBrowseViewModel.OpenEvent] with the correct isAudio flag.
 *
 * Chitanka's library_items population is on-demand (ADR 0042), so the emission MUST come after the
 * upsert — the screen collects openEvents to navigate. Reversing that order would race the reader's
 * `LibraryObserver.getItem` and land it on the "item not found → failed" branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChitankaBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val chitankaSource = Source(
        id = "chit-1",
        url = SourceUrl.parse("https://chitanka.info")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )

    private fun makeVm(
        rootId: String = ChitankaCatalog.ROOT_BOOKS,
        upserter: ChitankaLibraryItemUpserter = mockk(relaxed = true),
        sourceRepo: SourceRepository = fakeSourceRepo(chitankaSource),
    ): ChitankaBrowseViewModel {
        val registry = mockk<CatalogRegistry>()
        coEvery { registry.forSource(any()) } returns mockk<Catalog>(relaxed = true)
        val savedStateHandle = SavedStateHandle(mapOf("rootId" to rootId))
        return ChitankaBrowseViewModel(savedStateHandle, sourceRepo, registry, upserter)
    }

    private fun catalogEpub() = CatalogItem(
        id = "text/12345-x",
        rootId = ChitankaCatalog.ROOT_BOOKS,
        title = "T",
        author = "A",
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
    )

    private fun catalogAudio() = CatalogItem(
        id = "prikazki/1-slug",
        rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
        title = "T",
        author = "A",
        coverUrl = null,
        ebookFormat = BookFormat.Audiobook,
        hasAudio = true,
    )

    @Test
    fun `openItem on books root upserts then emits isAudio=false`() = runTest(dispatcher) {
        val upserter = mockk<ChitankaLibraryItemUpserter>(relaxed = true)
        val vm = makeVm(rootId = ChitankaCatalog.ROOT_BOOKS, upserter = upserter)
        advanceUntilIdle() // let init's loadFacets/refresh drain

        val item = catalogEpub()
        val emitted = backgroundScope.async(dispatcher) { vm.openEvents.first() }
        vm.openItem(item)
        advanceUntilIdle()

        coVerify(exactly = 1) { upserter.upsert("chit-1", item) }
        val event = emitted.await()
        assertEquals("text/12345-x", event.itemId)
        assertEquals(false, event.isAudio)
    }

    @Test
    fun `openItem on audiobooks root emits isAudio=true`() = runTest(dispatcher) {
        val upserter = mockk<ChitankaLibraryItemUpserter>(relaxed = true)
        val vm = makeVm(rootId = ChitankaCatalog.ROOT_AUDIOBOOKS, upserter = upserter)
        advanceUntilIdle()

        val item = catalogAudio()
        val emitted = backgroundScope.async(dispatcher) { vm.openEvents.first() }
        vm.openItem(item)
        advanceUntilIdle()

        coVerify(exactly = 1) { upserter.upsert("chit-1", item) }
        val event = emitted.await()
        assertEquals("prikazki/1-slug", event.itemId)
        assertEquals(true, event.isAudio)
    }

    @Test
    fun `openItem with no active Chitanka source no-ops (no upsert, no emit)`() = runTest(dispatcher) {
        val upserter = mockk<ChitankaLibraryItemUpserter>(relaxed = true)
        // Active source is a non-Chitanka type — the ViewModel refuses to touch it.
        val absSource = chitankaSource.copy(id = "abs-1", type = SourceType.ABS)
        val vm = makeVm(upserter = upserter, sourceRepo = fakeSourceRepo(absSource))
        advanceUntilIdle()

        vm.openItem(catalogEpub())
        advanceUntilIdle()

        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    // ---- helpers -----------------------------------------------------------------

    private fun fakeSourceRepo(active: Source?): SourceRepository = object : SourceRepository {
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(listOfNotNull(active))
        override suspend fun getActive(): Source? = active
        override suspend fun authenticate(
            url: SourceUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
        ) = throw UnsupportedOperationException()
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingSource,
            hiddenLibraryIds: Set<String>,
        ) = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) { }
        override suspend fun remove(sourceId: String) { }
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}
