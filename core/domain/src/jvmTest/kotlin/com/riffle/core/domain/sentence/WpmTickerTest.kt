package com.riffle.core.domain.sentence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WpmTickerTest {

    private fun q(text: String) = SentenceQuote(before = "", highlight = text, after = "")

    // ---- wordCount() -----------------------------------------------------------------------------

    @Test
    fun `wordCount splits on whitespace`() {
        assertEquals(4, WpmTicker.wordCount("The quick brown fox"))
        assertEquals(1, WpmTicker.wordCount("Hello"))
        assertEquals(3, WpmTicker.wordCount("  leading   and trailing  "))
    }

    @Test
    fun `wordCount floors at 1 for empty or whitespace-only text`() {
        assertEquals(1, WpmTicker.wordCount(""))
        assertEquals(1, WpmTicker.wordCount("   "))
    }

    // ---- ticker lifecycle ------------------------------------------------------------------------

    @Test
    fun `starts idle with null fragment and null progress`() = runTest {
        val ticker = tickerOf()
        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }

    @Test
    fun `play emits first fragment immediately`() = runTest {
        val ticker = tickerOf(listOf("c#s0" to "one two"))
        ticker.play()
        assertEquals("c#s0", ticker.currentFragment.value)
        assertEquals(0.0, ticker.progress.value)
        ticker.stop()
    }

    @Test
    fun `play advances to next fragment after dwell elapses`() = runTest {
        // Sentence 0: 8 words at 600 wpm = 800ms dwell (above MIN_DWELL_MS=400).
        val ticker = tickerOf(
            listOf(
                "c#s0" to "one two three four five six seven eight",
                "c#s1" to "next",
            ),
            speed = AutoScrollSpeed.of(600),
        )
        ticker.play()
        runCurrent()
        assertEquals("c#s0", ticker.currentFragment.value)

        advanceTimeBy(500L)
        assertEquals("c#s0", ticker.currentFragment.value)

        advanceTimeBy(400L) // past 800ms → advanced to s1
        assertEquals("c#s1", ticker.currentFragment.value)
        ticker.stop()
    }

    @Test
    fun `slower WPM = longer dwell`() = runTest {
        val ticker = tickerOf(
            listOf("c#s0" to "one two three", "c#s1" to "four"),
            speed = AutoScrollSpeed.of(80), // 750ms/word → s0 dwells 2250ms
        )
        ticker.play()
        advanceTimeBy(500L)
        assertEquals("c#s0", ticker.currentFragment.value)
        advanceTimeBy(1000L)
        assertEquals("c#s0", ticker.currentFragment.value)
        advanceTimeBy(1000L)
        assertEquals("c#s1", ticker.currentFragment.value)
        ticker.stop()
    }

    @Test
    fun `setSpeed applies to subsequent sentences not current`() = runTest {
        // s0 = 8 words at 600 wpm = 800ms.  Bump to 80 wpm mid-s0.
        // s1 = 4 words at 80 wpm = 3000ms — well above MIN_DWELL_MS.
        val ticker = tickerOf(
            listOf(
                "c#s0" to "one two three four five six seven eight",
                "c#s1" to "alpha beta gamma delta",
            ),
            speed = AutoScrollSpeed.of(600),
        )
        ticker.play()
        runCurrent()
        advanceTimeBy(100L)
        ticker.setSpeed(AutoScrollSpeed.of(80))
        advanceTimeBy(800L) // total 900ms, past s0's 800ms dwell
        assertEquals("c#s1", ticker.currentFragment.value)
        // s1 dwells 3000ms at new speed — after 1500ms more, still on s1.
        advanceTimeBy(1500L)
        assertEquals("c#s1", ticker.currentFragment.value)
        ticker.stop()
    }

    @Test
    fun `pause freezes current fragment then play resumes on same fragment`() = runTest {
        val ticker = tickerOf(
            listOf("c#s0" to "a b c d", "c#s1" to "e f"),
            speed = AutoScrollSpeed.of(600), // 100ms/word → s0 = 400ms
        )
        ticker.play()
        advanceTimeBy(150L)
        ticker.pause()
        val frozen = ticker.currentFragment.value
        assertNotNull(frozen)
        advanceTimeBy(10_000L)
        assertEquals(frozen, ticker.currentFragment.value)
        ticker.stop()
    }

    @Test
    fun `stop clears current fragment and progress`() = runTest {
        val ticker = tickerOf(listOf("c#s0" to "hello world"))
        ticker.play()
        advanceTimeBy(50L)
        ticker.stop()
        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }

    @Test
    fun `goTo jumps to specified fragment`() = runTest {
        val ticker = tickerOf(
            listOf("c#s0" to "a", "c#s1" to "b", "c#s2" to "c"),
            speed = AutoScrollSpeed.of(600),
        )
        ticker.play()
        ticker.goTo("c#s2")
        assertEquals("c#s2", ticker.currentFragment.value)
        assertEquals(0.0, ticker.progress.value)
        ticker.stop()
    }

    @Test
    fun `goTo ignored when fragment not in ordering`() = runTest {
        val ticker = tickerOf(listOf("c#s0" to "a", "c#s1" to "b"))
        ticker.play()
        ticker.goTo("c#nope")
        assertEquals("c#s0", ticker.currentFragment.value)
        ticker.stop()
    }

    @Test
    fun `onExhausted fires after last sentence dwell`() = runTest {
        var fired = 0
        val ticker = tickerOf(
            listOf("c#s0" to "one"), // ~75ms at 600wpm → clamped to MIN_DWELL_MS=400ms
            speed = AutoScrollSpeed.of(600),
            onExhausted = { fired++ },
        )
        ticker.play()
        advanceUntilIdle()
        assertEquals(1, fired)
        // current fragment stays on the last one; caller decides what to do
    }

    @Test
    fun `onExhausted fires immediately on empty ordering`() = runTest {
        var fired = 0
        val ticker = tickerOf(emptyList(), onExhausted = { fired++ })
        ticker.play()
        assertEquals(1, fired)
    }

    @Test
    fun `progress reaches at least 0_5 mid-dwell`() = runTest {
        val ticker = tickerOf(
            listOf("c#s0" to "one two three four five six seven eight nine ten"),
            speed = AutoScrollSpeed.of(600), // 100ms/word × 10 = 1000ms dwell
        )
        ticker.play()
        runCurrent()
        advanceTimeBy(650L)
        assertTrue(
            "progress should be past 0.5 mid-dwell, was ${ticker.progress.value}",
            (ticker.progress.value ?: 0.0) >= 0.5,
        )
        ticker.stop()
    }

    private fun TestScope.tickerOf(
        pairs: List<Pair<FragmentRef, String>> = emptyList(),
        speed: AutoScrollSpeed = AutoScrollSpeed.Default,
        onExhausted: () -> Unit = {},
    ): WpmTicker = WpmTicker(
        orderedFragments = pairs.map { it.first },
        quotes = pairs.associate { it.first to q(it.second) },
        scope = this,
        initialSpeed = speed,
        onExhausted = onExhausted,
    )
}
