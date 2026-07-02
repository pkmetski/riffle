package com.riffle.core.domain.sentence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract test that verifies the [SentenceTicker] interface compiles clean and can be
 * exercised by a simple fake implementation. Validates state transitions:
 * play → currentFragment emits, pause → stops emitting, stop → currentFragment goes null.
 */
class SentenceTickerContractTest {

    private class FakeSentenceTicker(
        initFragment: FragmentRef? = null,
    ) : SentenceTicker {
        override val currentFragment: StateFlow<FragmentRef?> = MutableStateFlow(initFragment)
        override val progress: StateFlow<Double?> = MutableStateFlow(null)

        private var isPlaying = false

        override fun play() {
            isPlaying = true
            // In a real implementation, would begin emitting currentFragment + progress updates
            (currentFragment as MutableStateFlow).value = "chapter1.xhtml#s0"
            (progress as MutableStateFlow).value = 0.0
        }

        override fun pause() {
            isPlaying = false
            // In a real implementation, would stop emitting updates but retain current position
        }

        override fun stop() {
            isPlaying = false
            (currentFragment as MutableStateFlow).value = null
            (progress as MutableStateFlow).value = null
        }

        override fun goTo(fragment: FragmentRef) {
            (currentFragment as MutableStateFlow).value = fragment
            (progress as MutableStateFlow).value = 0.0
        }
    }

    @Test
    fun `FakeSentenceTicker starts idle with null fragment`() {
        val ticker = FakeSentenceTicker()
        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }

    @Test
    fun `play() emits a currentFragment`() {
        val ticker = FakeSentenceTicker()
        ticker.play()
        assertEquals("chapter1.xhtml#s0", ticker.currentFragment.value)
        assertEquals(0.0, ticker.progress.value)
    }

    @Test
    fun `pause() retains currentFragment and progress`() {
        val ticker = FakeSentenceTicker()
        ticker.play()
        val fragment = ticker.currentFragment.value
        val prog = ticker.progress.value
        ticker.pause()
        assertEquals(fragment, ticker.currentFragment.value)
        assertEquals(prog, ticker.progress.value)
    }

    @Test
    fun `stop() clears currentFragment and progress`() {
        val ticker = FakeSentenceTicker()
        ticker.play()
        ticker.stop()
        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }

    @Test
    fun `goTo() updates currentFragment and resets progress`() {
        val ticker = FakeSentenceTicker()
        ticker.play()
        ticker.goTo("chapter2.xhtml#s5")
        assertEquals("chapter2.xhtml#s5", ticker.currentFragment.value)
        assertEquals(0.0, ticker.progress.value)
    }
}
