package com.riffle.app.feature.reader.controllers

import android.net.FakeUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
