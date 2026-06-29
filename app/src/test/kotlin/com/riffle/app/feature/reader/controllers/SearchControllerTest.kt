package com.riffle.app.feature.reader.controllers

import android.net.FakeUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {

    private fun makeController(): SearchController {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        return SearchController(scope = scope)
    }

    // --- Tests ---

    @Test
    fun `openSearch sets isSearchActive to true`() = runTest {
        val controller = makeController()
        assertFalse(controller.isSearchActive.value)
        controller.openSearch()
        assertTrue(controller.isSearchActive.value)
    }

    @Test
    fun `closeSearch clears query, results, and index`() = runTest {
        val controller = makeController()
        controller.openSearch()
        controller.onSearchQueryChanged("hello")
        controller.closeSearch()
        assertFalse(controller.isSearchActive.value)
        assertEquals("", controller.searchQuery.value)
        assertEquals(emptyList<Locator>(), controller.searchResults.value)
        assertEquals(-1, controller.currentSearchIndex.value)
    }

    @Test
    fun `onSearchQueryChanged updates searchQuery`() = runTest {
        val controller = makeController()
        controller.onSearchQueryChanged("kotlin")
        assertEquals("kotlin", controller.searchQuery.value)
    }

    @Test
    fun `nextSearchResult cycles index forward and emits to channel`() = runTest {
        val controller = makeController()
        val loc0 = buildLocator("ch1.xhtml", 0.0)
        val loc1 = buildLocator("ch1.xhtml", 0.5)
        val loc2 = buildLocator("ch2.xhtml", 0.0)
        // Inject with startIndex=0 (initial nav event for loc0 is sent by inject).
        controller.injectResultsForTest(listOf(loc0, loc1, loc2), startIndex = 0)

        val navEvents = mutableListOf<Locator>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher()) {
            controller.searchNavigationEvents.toList(navEvents)
        }

        controller.nextSearchResult()
        assertEquals(1, controller.currentSearchIndex.value)

        controller.nextSearchResult()
        assertEquals(2, controller.currentSearchIndex.value)

        // At the end: stays at last index (but still emits the clamped locator again, consistent
        // with the VM's original behaviour which always calls trySend on next/prev).
        controller.nextSearchResult()
        assertEquals(2, controller.currentSearchIndex.value)

        collectJob.cancel()
        // inject emits loc0, then next→loc1, next→loc2, next(clamped)→loc2 again = 4 total
        assertEquals(4, navEvents.size)
    }

    @Test
    fun `prevSearchResult cycles index backward and emits to channel`() = runTest {
        val controller = makeController()
        val loc0 = buildLocator("ch1.xhtml", 0.0)
        val loc1 = buildLocator("ch1.xhtml", 0.5)
        // Inject with startIndex=1 (initial nav event for loc1 is sent by inject).
        controller.injectResultsForTest(listOf(loc0, loc1), startIndex = 1)

        val navEvents = mutableListOf<Locator>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher()) {
            controller.searchNavigationEvents.toList(navEvents)
        }

        controller.prevSearchResult()
        assertEquals(0, controller.currentSearchIndex.value)

        // Already at 0: stays and still emits (same as VM behaviour)
        controller.prevSearchResult()
        assertEquals(0, controller.currentSearchIndex.value)

        collectJob.cancel()
        // inject emits loc1, prev→loc0, prev(clamped)→loc0 again = 3 total
        assertEquals(3, navEvents.size)
    }

    @Test
    fun `closeSearch resets after results populated`() = runTest {
        val controller = makeController()
        val loc = buildLocator("ch1.xhtml", 0.1)
        controller.injectResultsForTest(listOf(loc), startIndex = 0)
        assertTrue(controller.isSearchActive.value)

        controller.closeSearch()
        assertFalse(controller.isSearchActive.value)
        assertEquals(emptyList<Locator>(), controller.searchResults.value)
        assertEquals(-1, controller.currentSearchIndex.value)
        assertEquals("", controller.searchQuery.value)
    }

    @Test
    fun `onSearchQueryChanged debounces at 300ms before performSearch`() = runTest {
        // Use StandardTestDispatcher so coroutine time is manually controlled.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val controller = SearchController(scope = scope)

        // bind(null) starts the debounce coroutine. publication=null means performSearch
        // exits early, but the debounce *fires* and applies the query-length<2 branch,
        // which explicitly resets currentSearchIndex to -1.
        controller.bind(null)
        // Drain the initial bind reset coroutines.
        testScheduler.advanceUntilIdle()

        // Put the controller into a non-default state so we can observe the debounce firing.
        // Use the test helper to set currentSearchIndex=0 without going through the debounce.
        val loc = buildLocator("ch1.xhtml", 0.0)
        controller.setResultsForTest(listOf(loc), startIndex = 0)
        assertEquals(0, controller.currentSearchIndex.value)

        // Change query to a short string (length < 2). When the debounce fires it will
        // reset currentSearchIndex back to -1. Before 300ms it must not have fired.
        controller.onSearchQueryChanged("f")

        // --- Before 300 ms: debounce has NOT fired ---
        advanceTimeBy(299)
        assertEquals(
            "currentSearchIndex should still be 0 at 299ms — debounce not yet elapsed",
            0,
            controller.currentSearchIndex.value,
        )

        // --- At 300 ms: debounce fires, short-query branch sets index = -1 ---
        advanceTimeBy(1)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "currentSearchIndex should be -1 at 300ms — debounce fired and reset state",
            -1,
            controller.currentSearchIndex.value,
        )
    }

    // --- Helpers ---

    /**
     * Bypasses the debounce+publication.search path: directly injects test results
     * into the controller's state so navigation tests don't need a real Publication.
     */
    private fun SearchController.injectResultsForTest(results: List<Locator>, startIndex: Int) {
        openSearch()
        setResultsForTest(results, startIndex)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildLocator(href: String, progression: Double): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri(href))
        return Locator(
            href = url,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
        )
    }
}
