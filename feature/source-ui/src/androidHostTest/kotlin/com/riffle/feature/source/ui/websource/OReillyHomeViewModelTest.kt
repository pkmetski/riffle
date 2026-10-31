package com.riffle.feature.source.ui.websource

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ReadingHistoryCapability
import com.riffle.core.catalog.oreilly.OReillyCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OReillyHomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val oreillySource = Source(
        id = "oreilly-1",
        url = SourceUrl.parse("https://learning.oreilly.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.OREILLY,
    )

    private fun inProgressItem(id: String, progress: Float) = CatalogItem(
        id = id,
        rootId = OReillyCatalog.ROOT_BOOKS,
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
        readingProgress = progress,
    )

    private fun makeVm(
        history: List<CatalogItem> = emptyList(),
        sourceRepo: SourceRepository = fakeSourceRepo(oreillySource),
        failWith: Throwable? = null,
    ): OReillyHomeViewModel {
        val catalog = mockk<OReillyCatalog>(relaxed = true)
        if (failWith != null) {
            coEvery { catalog.fetchReadingHistory(any()) } throws failWith
        } else {
            coEvery { catalog.fetchReadingHistory(any()) } returns history
        }
        val catalogRegistry = mockk<CatalogRegistry> {
            coEvery { forSource(any()) } returns catalog
        }
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)
        val gate = mockk<WebSourceItemGate>(relaxed = true)
        return OReillyHomeViewModel(
            sourceRepository = sourceRepo,
            catalogRegistry = catalogRegistry,
            libraryItemUpserter = upserter,
            webSourceItemGate = gate,
            dispatchers = object : DispatcherProvider {
                override val main get() = dispatcher
                override val io get() = dispatcher
                override val default get() = dispatcher
            },
        )
    }

    @Test
    fun inProgressContainsItemsBelowThreshold() = runTest {
        val vm = makeVm(
            history = listOf(
                inProgressItem("a", 0.45f),
                inProgressItem("b", 0.80f),
                inProgressItem("c", 0.95f),
            ),
        )
        advanceUntilIdle()
        val ip = vm.inProgress.value
        assertEquals(2, ip.size)
        assertTrue(ip.any { it.id == "a" })
        assertTrue(ip.any { it.id == "b" })
    }

    @Test
    fun completedContainsItemsAtOrAboveThreshold() = runTest {
        val vm = makeVm(
            history = listOf(
                inProgressItem("a", 0.45f),
                inProgressItem("b", 0.90f),
                inProgressItem("c", 0.99f),
            ),
        )
        advanceUntilIdle()
        val c = vm.completed.value
        assertEquals(2, c.size)
        assertTrue(c.any { it.id == "b" })
        assertTrue(c.any { it.id == "c" })
    }

    @Test
    fun isEmptyTrueWhenNoHistory() = runTest {
        val vm = makeVm(history = emptyList())
        advanceUntilIdle()
        assertTrue(vm.isEmpty.value)
    }

    @Test
    fun errorIsSetOnFailure() = runTest {
        val vm = makeVm(failWith = RuntimeException("Network error"))
        advanceUntilIdle()
        assertTrue(vm.error.value != null)
        assertTrue(vm.inProgress.value.isEmpty())
        assertTrue(vm.completed.value.isEmpty())
    }

    @Test
    fun isLoadingFalseAfterFetch() = runTest {
        val vm = makeVm(history = emptyList())
        advanceUntilIdle()
        assertTrue(!vm.isLoading.value)
    }

    @Test
    fun noFetchWhenNoOReillySource() = runTest {
        val vm = makeVm(
            history = listOf(inProgressItem("x", 0.5f)),
            sourceRepo = fakeSourceRepo(null),
        )
        advanceUntilIdle()
        assertTrue(vm.inProgress.value.isEmpty())
    }

    private fun fakeSourceRepo(active: Source?): SourceRepository = object : SourceRepository {
        override fun observeAll() = flowOf(listOfNotNull(active))
        override suspend fun getActive(): Source? = active
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingSource,
            hiddenLibraryIds: Set<String>,
        ) = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}
