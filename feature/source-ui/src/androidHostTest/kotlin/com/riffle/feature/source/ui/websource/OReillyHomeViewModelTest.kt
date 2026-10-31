package com.riffle.feature.source.ui.websource

import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import io.mockk.every
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

    private val libraryId = "oreilly-lib-1"

    private fun libraryItem(id: String, progress: Float, hasAudio: Boolean = false) = LibraryItem(
        id = id,
        libraryId = libraryId,
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        readingProgress = progress,
        isCached = false,
        isDownloaded = false,
        ebookFormat = if (hasAudio) EbookFormat.Unsupported else EbookFormat.Epub,
        hasAudio = hasAudio,
    )

    private fun makeVm(
        inProgress: List<LibraryItem> = emptyList(),
        finished: List<LibraryItem> = emptyList(),
    ): OReillyHomeViewModel {
        val observer = mockk<LibraryObserver>(relaxed = true)
        every { observer.observeInProgressItems(libraryId) } returns flowOf(inProgress)
        every { observer.observeFinishedItems(libraryId) } returns flowOf(finished)
        return OReillyHomeViewModel(libraryId = libraryId, libraryObserver = observer)
    }

    @Test
    fun inProgressExposesObservedInProgressItems() = runTest {
        val items = listOf(libraryItem("a", 0.45f), libraryItem("b", 0.80f))
        val vm = makeVm(inProgress = items)
        advanceUntilIdle()
        val ip = vm.inProgress.value
        assertEquals(2, ip.size)
        assertTrue(ip.any { it.id == "a" })
        assertTrue(ip.any { it.id == "b" })
    }

    @Test
    fun completedExposesObservedFinishedItems() = runTest {
        val items = listOf(libraryItem("b", 0.90f), libraryItem("c", 1.0f))
        val vm = makeVm(finished = items)
        advanceUntilIdle()
        val c = vm.completed.value
        assertEquals(2, c.size)
        assertTrue(c.any { it.id == "b" })
        assertTrue(c.any { it.id == "c" })
    }

    @Test
    fun isEmptyTrueWhenBothSectionsEmpty() = runTest {
        val vm = makeVm()
        advanceUntilIdle()
        assertTrue(vm.isEmpty.value)
    }

    @Test
    fun isEmptyFalseWhenInProgressHasItems() = runTest {
        val vm = makeVm(inProgress = listOf(libraryItem("a", 0.5f)))
        advanceUntilIdle()
        assertTrue(!vm.isEmpty.value)
    }

    @Test
    fun isEmptyFalseWhenCompletedHasItems() = runTest {
        val vm = makeVm(finished = listOf(libraryItem("b", 1.0f)))
        advanceUntilIdle()
        assertTrue(!vm.isEmpty.value)
    }
}
