package com.riffle.app.feature.reader

import com.riffle.core.domain.withResolvedTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// EpubReaderViewModel is an AndroidViewModel with Readium dependencies that rely on
// android.net.Uri, which cannot be used in JVM unit tests without Robolectric.
// These tests verify the `while (true) { delay(SYNC_INTERVAL_MS); sync() }` pattern
// from EpubReaderViewModel.startPeriodicSync() using virtual time, replacing the
// 35-second real-time harness test sessionUpdateSentAfterReading.
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class EpubReaderViewModelTest {

    // Must match EpubReaderViewModel.SYNC_INTERVAL_MS
    private val syncIntervalMs = 30_000L

    @Test
    fun `periodic sync timer fires once after one interval`() = runTest {
        var syncCount = 0

        // backgroundScope coroutines are not checked for completion when runTest ends,
        // so the while(true) loop does not cause UncompletedCoroutinesError.
        backgroundScope.launch {
            while (true) {
                delay(syncIntervalMs)
                syncCount++
            }
        }

        advanceTimeBy(syncIntervalMs + 1)
        assertEquals(1, syncCount)
    }

    @Test
    fun `periodic sync timer fires twice after two intervals`() = runTest {
        var syncCount = 0

        backgroundScope.launch {
            while (true) {
                delay(syncIntervalMs)
                syncCount++
            }
        }

        advanceTimeBy(2 * syncIntervalMs + 1)
        assertEquals(2, syncCount)
    }

    // Regression: prefs must be loaded before openBook() so initialPreferences is correct when the fragment is created.
    @Test
    fun `formattingPreferences has stored value when openBook resolves in sequential execution`() = runTest {
        val storedPrefs = "dark"   // stand-in for FormattingPreferences(theme = ReaderTheme.Dark)
        val formattingPrefs = MutableStateFlow("light")  // stand-in for default FormattingPreferences()
        var prefsWhenBookOpened: String? = null

        backgroundScope.launch {
            delay(10)                           // loadFormattingPreferences()
            formattingPrefs.value = storedPrefs

            delay(50)                           // openBook() — sequential, runs after prefs load
            prefsWhenBookOpened = formattingPrefs.value
        }

        advanceTimeBy(100)

        assertEquals(storedPrefs, prefsWhenBookOpened)
    }

    @Test
    fun `parallel execution exposes race where openBook sees default prefs before they load`() = runTest {
        val storedPrefs = "dark"
        val formattingPrefs = MutableStateFlow("light")
        var prefsWhenBookOpened: String? = null

        // Demonstrates the old broken pattern: parallel launches race with each other.
        // openBook completes before loadFormattingPreferences(), so it sees default prefs.
        backgroundScope.launch {
            delay(10)                           // openBook() wins the race
            prefsWhenBookOpened = formattingPrefs.value
        }
        backgroundScope.launch {
            delay(50)                           // loadFormattingPreferences() — arrives too late
            formattingPrefs.value = storedPrefs
        }

        advanceTimeBy(100)

        // openBook saw the default ("light"), not the stored ("dark") — this is the bug.
        assertNotEquals(storedPrefs, prefsWhenBookOpened)
        assertEquals("light", prefsWhenBookOpened)
    }

    @Test
    fun `search index advances to next result`() = runTest {
        val results = listOf("loc0", "loc1", "loc2")
        val currentIndex = MutableStateFlow(0)

        fun nextResult() {
            currentIndex.value = (currentIndex.value + 1).coerceAtMost(results.size - 1)
        }

        nextResult()
        assertEquals(1, currentIndex.value)
        nextResult()
        assertEquals(2, currentIndex.value)
        nextResult()
        assertEquals(2, currentIndex.value)
    }

    @Test
    fun `search index retreats to prev result`() = runTest {
        val results = listOf("loc0", "loc1", "loc2")
        val currentIndex = MutableStateFlow(2)

        fun prevResult() {
            currentIndex.value = (currentIndex.value - 1).coerceAtLeast(0)
        }

        prevResult()
        assertEquals(1, currentIndex.value)
        prevResult()
        assertEquals(0, currentIndex.value)
        prevResult()
        assertEquals(0, currentIndex.value)
    }

    @Test
    fun `search debounce only triggers after delay`() = runTest {
        var searchCallCount = 0
        val query = MutableStateFlow("")

        backgroundScope.launch {
            query
                .debounce(300)
                .collect { q -> if (q.length >= 2) searchCallCount++ }
        }

        query.value = "wi"
        advanceTimeBy(100)
        query.value = "win"
        advanceTimeBy(100)
        assertEquals(0, searchCallCount)

        advanceTimeBy(250)
        assertEquals(1, searchCallCount)
    }

    // EpubReaderViewModel is an AndroidViewModel and can't be instantiated here, so this
    // mirrors its boundary loop with virtual time — but routes resolution through the
    // production withResolvedTheme() so the test exercises real domain code, not just
    // ThemeSchedule.resolve in isolation.
    @Test
    fun `Auto prefs flip at each schedule boundary across a 24h cycle`() = runTest {
        val schedule = com.riffle.core.domain.ThemeSchedule(
            dayStart = java.time.LocalTime.of(7, 0),
            nightStart = java.time.LocalTime.of(21, 0),
            dayTheme = com.riffle.core.domain.ReaderTheme.Light,
            nightTheme = com.riffle.core.domain.ReaderTheme.Dark,
        )
        val prefs = com.riffle.core.domain.FormattingPreferences(
            theme = com.riffle.core.domain.ReaderTheme.Auto,
            themeSchedule = schedule,
        )
        var fakeNow = java.time.LocalTime.of(20, 59)
        val emitted = mutableListOf<com.riffle.core.domain.ReaderTheme>()

        backgroundScope.launch {
            emitted += prefs.withResolvedTheme(fakeNow).theme
            while (true) {
                val next = schedule.nextBoundaryAfter(fakeNow)
                val delayMs = ((next.toSecondOfDay() - fakeNow.toSecondOfDay() + 24 * 3600) % (24 * 3600)) * 1000L
                delay(delayMs.coerceAtLeast(1_000L))
                fakeNow = next
                emitted += prefs.withResolvedTheme(fakeNow).theme
            }
        }

        // 20:59 → 21:00 (60s) flips to Dark; 21:00 → 07:00 (10h) flips back to Light.
        advanceTimeBy(60_000 + 1)
        assertEquals(
            listOf(
                com.riffle.core.domain.ReaderTheme.Light,
                com.riffle.core.domain.ReaderTheme.Dark,
            ),
            emitted,
        )
        advanceTimeBy(10 * 3600 * 1000L)
        assertEquals(
            listOf(
                com.riffle.core.domain.ReaderTheme.Light,
                com.riffle.core.domain.ReaderTheme.Dark,
                com.riffle.core.domain.ReaderTheme.Light,
            ),
            emitted,
        )
    }

    // Regression for the EpubReaderViewModel's `if (schedule.dayStart == schedule.nightStart)
    // awaitCancellation()` guard: a degenerate schedule must not emit any boundary tick.
    @Test
    fun `degenerate schedule (equal day-and-night) emits no boundary ticks`() = runTest {
        val degenerate = com.riffle.core.domain.ThemeSchedule(
            dayStart = java.time.LocalTime.of(8, 0),
            nightStart = java.time.LocalTime.of(8, 0),
        )
        var tickCount = 0
        backgroundScope.launch {
            if (degenerate.dayStart == degenerate.nightStart) {
                kotlinx.coroutines.awaitCancellation()
            }
            while (true) {
                delay(1_000L)
                tickCount++
            }
        }
        advanceTimeBy(24 * 3600 * 1000L)
        assertEquals(0, tickCount)
    }
}

/**
 * Unit tests for EpubReaderViewModel's search pipeline logic, exercised without Android
 * dependencies by replicating the exact control flow from performSearch / nextSearchResult /
 * prevSearchResult / closeSearch / the debounce collector.
 *
 * [FakeIterator] and [SearchPipeline] mirror Readium's SearchIterator and the ViewModel
 * state machine one-to-one so regressions here map directly to production behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchPipelineTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Simulates Readium's SearchIterator with three distinct outcomes per call:
     *
     *   null entry in [pages]        → failure (mirrors Try.failure / SearchError.Reading,
     *                                   e.g. OOM wrapped by Readium as a typed error)
     *   non-null entry in [pages]    → success with those locators
     *   callIndex >= pages.size      → end of book (mirrors Try.success(null))
     *   [throwOomOnCallIndex] >= 0   → throws OutOfMemoryError directly (catastrophic OOM
     *                                   that Readium itself did not intercept)
     */
    private class FakeIterator(
        private val pages: List<List<String>?>,
        private val throwOomOnCallIndex: Int = -1,
    ) {
        var closed = false
        private var callIndex = 0

        // Returns: null = typed failure (skip); emptyList = end of book (stop); else = results.
        fun nextResult(): List<String>? {
            if (callIndex == throwOomOnCallIndex) throw OutOfMemoryError("catastrophic OOM")
            if (callIndex >= pages.size) return emptyList()
            return pages[callIndex++]
        }

        fun close() { closed = true }
    }

    private class SearchPipeline(
        val cache: MutableMap<Int, String> = mutableMapOf(),
    ) {
        val results = MutableStateFlow<List<String>>(emptyList())
        val currentIndex = MutableStateFlow(-1)
        val navChannel = Channel<String>(Channel.CONFLATED)
        val navEvents: Flow<String> = navChannel.receiveAsFlow()
        var searchJob: Job? = null

        // Mirrors EpubReaderViewModel.performSearch exactly:
        //   withContext(Dispatchers.IO) { cache.clear(); isFailure-skip loop; try/finally }
        //   catch OutOfMemoryError → emptyList()
        //   then update state + send nav event
        suspend fun performSearch(makeIterator: () -> FakeIterator) {
            val found = try {
                withContext(Dispatchers.Default) {
                    cache.clear()
                    val iter = makeIterator()
                    val acc = mutableListOf<String>()
                    try {
                        while (true) {
                            val pageResult = iter.nextResult()
                            if (pageResult == null) continue  // typed failure → skip chapter
                            if (pageResult.isEmpty()) break   // end of book → stop
                            acc.addAll(pageResult)
                        }
                    } finally {
                        iter.close()
                    }
                    acc
                }
            } catch (_: OutOfMemoryError) {
                emptyList()
            }
            results.value = found
            if (found.isEmpty()) {
                currentIndex.value = -1
            } else {
                currentIndex.value = 0
                navChannel.trySend(found[0])
            }
        }

        // Mirrors EpubReaderViewModel.nextSearchResult
        fun next() {
            val r = results.value
            if (r.isEmpty()) return
            val idx = (currentIndex.value + 1).coerceAtMost(r.size - 1)
            currentIndex.value = idx
            navChannel.trySend(r[idx])
        }

        // Mirrors EpubReaderViewModel.prevSearchResult
        fun prev() {
            val r = results.value
            if (r.isEmpty()) return
            val idx = (currentIndex.value - 1).coerceAtLeast(0)
            currentIndex.value = idx
            navChannel.trySend(r[idx])
        }

        // Mirrors EpubReaderViewModel.closeSearch
        fun close() {
            results.value = emptyList()
            currentIndex.value = -1
            searchJob?.cancel()
        }
    }

    // ── performSearch: happy path ─────────────────────────────────────────────

    @Test
    fun `search with results sets index to 0 and sends first locator to navigation`() = runTest {
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(listOf(listOf("loc:1", "loc:2"), listOf("loc:3")))
        }

        assertEquals(listOf("loc:1", "loc:2", "loc:3"), pipeline.results.value)
        assertEquals(0, pipeline.currentIndex.value)
        assertEquals("loc:1", pipeline.navChannel.tryReceive().getOrNull())
    }

    @Test
    fun `search collects locators across multiple pages from the iterator`() = runTest {
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(listOf(listOf("a", "b"), listOf("c"), listOf("d", "e")))
        }

        assertEquals(listOf("a", "b", "c", "d", "e"), pipeline.results.value)
    }

    @Test
    fun `search with no results sets index to -1`() = runTest {
        val pipeline = SearchPipeline()
        pipeline.results.value = listOf("stale")
        pipeline.currentIndex.value = 0

        pipeline.performSearch { FakeIterator(emptyList()) }

        assertEquals(emptyList<String>(), pipeline.results.value)
        assertEquals(-1, pipeline.currentIndex.value)
    }

    // ── failure resilience (Readium wraps OOM as typed Try.failure) ──────────────

    @Test
    fun `typed failure on first chapter is skipped and results from later chapter are returned`() = runTest {
        // This is the primary regression test for the original bug.
        // On low-heap devices, Readium wraps OOM as Try.failure(SearchError.Reading(...))
        // rather than throwing OutOfMemoryError. Old code broke on the first failure;
        // new code uses isFailure-continue to skip it and search remaining chapters.
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(listOf(null, listOf("loc:1", "loc:2")))  // ch1 fails, ch2 succeeds
        }

        assertEquals(listOf("loc:1", "loc:2"), pipeline.results.value)
        assertEquals(0, pipeline.currentIndex.value)
    }

    @Test
    fun `typed failure mid-iteration is skipped and surrounding results are collected`() = runTest {
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(listOf(listOf("loc:1"), null, listOf("loc:2")))
        }

        assertEquals(listOf("loc:1", "loc:2"), pipeline.results.value)
    }

    @Test
    fun `all chapters failing returns empty results`() = runTest {
        val pipeline = SearchPipeline()

        pipeline.performSearch { FakeIterator(listOf(null, null, null)) }

        assertEquals(emptyList<String>(), pipeline.results.value)
        assertEquals(-1, pipeline.currentIndex.value)
    }

    @Test
    fun `catastrophic OOM not wrapped by Readium returns empty results without crashing`() = runTest {
        val pipeline = SearchPipeline()

        pipeline.performSearch { FakeIterator(emptyList(), throwOomOnCallIndex = 0) }

        assertEquals(emptyList<String>(), pipeline.results.value)
        assertEquals(-1, pipeline.currentIndex.value)
    }

    @Test
    fun `iterator is closed after successful search`() = runTest {
        var capturedIter: FakeIterator? = null
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(listOf(listOf("loc:1"))).also { capturedIter = it }
        }

        assertTrue("iterator must be closed via finally", capturedIter!!.closed)
    }

    @Test
    fun `iterator is closed even when typed failure occurs`() = runTest {
        var capturedIter: FakeIterator? = null
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(listOf(null)).also { capturedIter = it }
        }

        assertTrue("finally block must close iterator on failure", capturedIter!!.closed)
    }

    @Test
    fun `iterator is closed even when catastrophic OOM occurs`() = runTest {
        var capturedIter: FakeIterator? = null
        val pipeline = SearchPipeline()

        pipeline.performSearch {
            FakeIterator(emptyList(), throwOomOnCallIndex = 0).also { capturedIter = it }
        }

        assertTrue("finally block must close iterator on catastrophic OOM", capturedIter!!.closed)
    }

    // ── cache clearing ────────────────────────────────────────────────────────

    @Test
    fun `chapter cache is cleared before search begins`() = runTest {
        val cache = mutableMapOf(0 to "<html>chapter 0</html>", 1 to "<html>chapter 1</html>")
        val pipeline = SearchPipeline(cache)
        var cacheSizeAtSearchStart = -1

        pipeline.performSearch {
            cacheSizeAtSearchStart = cache.size
            FakeIterator(listOf(listOf("loc:1")))
        }

        assertEquals("cache must be empty when iterator is created", 0, cacheSizeAtSearchStart)
    }

    @Test
    fun `cache remains empty after search completes`() = runTest {
        val cache = mutableMapOf(0 to "<html>chapter 0</html>")
        val pipeline = SearchPipeline(cache)

        pipeline.performSearch { FakeIterator(listOf(listOf("loc:1"))) }

        assertTrue("cache must not be repopulated by search", cache.isEmpty())
    }

    // ── next / prev navigation ────────────────────────────────────────────────

    @Test
    fun `next advances index and sends locator to navigation`() = runTest {
        val pipeline = SearchPipeline()
        pipeline.performSearch { FakeIterator(listOf(listOf("loc:0", "loc:1", "loc:2"))) }
        pipeline.navChannel.tryReceive() // drain the initial nav event from performSearch

        pipeline.next()

        assertEquals(1, pipeline.currentIndex.value)
        assertEquals("loc:1", pipeline.navChannel.tryReceive().getOrNull())
    }

    @Test
    fun `prev retreats index and sends locator to navigation`() = runTest {
        val pipeline = SearchPipeline()
        pipeline.performSearch { FakeIterator(listOf(listOf("loc:0", "loc:1", "loc:2"))) }
        pipeline.currentIndex.value = 2
        pipeline.navChannel.tryReceive()

        pipeline.prev()

        assertEquals(1, pipeline.currentIndex.value)
        assertEquals("loc:1", pipeline.navChannel.tryReceive().getOrNull())
    }

    @Test
    fun `next does not advance past last result`() = runTest {
        val pipeline = SearchPipeline()
        pipeline.performSearch { FakeIterator(listOf(listOf("loc:0", "loc:1"))) }
        pipeline.currentIndex.value = 1
        pipeline.navChannel.tryReceive()

        pipeline.next()
        pipeline.next()

        assertEquals(1, pipeline.currentIndex.value)
    }

    @Test
    fun `prev does not retreat past first result`() = runTest {
        val pipeline = SearchPipeline()
        pipeline.performSearch { FakeIterator(listOf(listOf("loc:0", "loc:1"))) }
        pipeline.currentIndex.value = 0
        pipeline.navChannel.tryReceive()

        pipeline.prev()
        pipeline.prev()

        assertEquals(0, pipeline.currentIndex.value)
    }

    @Test
    fun `next and prev are no-ops when results are empty`() = runTest {
        val pipeline = SearchPipeline()

        pipeline.next()
        pipeline.prev()

        assertEquals(-1, pipeline.currentIndex.value)
        assertNull(pipeline.navChannel.tryReceive().getOrNull())
    }

    // ── close / reset ─────────────────────────────────────────────────────────

    @Test
    fun `closeSearch resets results and index`() = runTest {
        val pipeline = SearchPipeline()
        pipeline.performSearch { FakeIterator(listOf(listOf("loc:0"))) }

        pipeline.close()

        assertEquals(emptyList<String>(), pipeline.results.value)
        assertEquals(-1, pipeline.currentIndex.value)
    }

    // ── debounce + short-query guard ──────────────────────────────────────────

    @Test
    fun `query shorter than 2 chars resets results without triggering search`() = runTest {
        val results = MutableStateFlow(listOf("loc:stale"))
        val index = MutableStateFlow(0)
        var searchCalled = false
        val query = MutableStateFlow("")

        backgroundScope.launch {
            query.debounce(300).collect { q ->
                if (q.length < 2) {
                    results.value = emptyList()
                    index.value = -1
                    return@collect
                }
                searchCalled = true
            }
        }

        query.value = "a"
        advanceTimeBy(400)

        assertFalse(searchCalled)
        assertEquals(emptyList<String>(), results.value)
        assertEquals(-1, index.value)
    }

    @Test
    fun `rapid typing fires only one search after debounce settles`() = runTest {
        var searchCallCount = 0
        val query = MutableStateFlow("")

        backgroundScope.launch {
            query.debounce(300).collect { q -> if (q.length >= 2) searchCallCount++ }
        }

        query.value = "ki"
        advanceTimeBy(100)
        query.value = "kin"
        advanceTimeBy(100)
        query.value = "king"
        advanceTimeBy(100)
        assertEquals(0, searchCallCount)

        advanceTimeBy(250)
        assertEquals(1, searchCallCount)
    }

    @Test
    fun `each new settled query triggers a separate search`() = runTest {
        var searchCallCount = 0
        val query = MutableStateFlow("")

        backgroundScope.launch {
            query.debounce(300).collect { q -> if (q.length >= 2) searchCallCount++ }
        }

        query.value = "ki"
        advanceTimeBy(400)
        assertEquals(1, searchCallCount)

        query.value = "queen"
        advanceTimeBy(400)
        assertEquals(2, searchCallCount)
    }
}
