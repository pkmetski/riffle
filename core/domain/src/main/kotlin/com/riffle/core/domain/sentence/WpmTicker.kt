package com.riffle.core.domain.sentence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [SentenceTicker] driven by a WPM (words-per-minute) timer — Cadence's tick source.
 *
 * Dwell for each sentence is `wordCount(sentence) / wpm` minutes. When the dwell elapses, the ticker
 * advances to the next fragment (in the order returned by [orderedFragments]); at the end, it
 * signals via [onExhausted] so the outer controller can auto-advance to the next chapter or stop.
 *
 * WPM can be changed at any time (volume-key nudge, Settings slider) via [setSpeed]; the change
 * applies from the next tick onwards — the current sentence's remaining dwell is not re-scaled.
 *
 * Purely coroutine-driven so it is virtual-time testable with `runTest`. No Android or WebView deps.
 */
class WpmTicker(
    private val orderedFragments: List<FragmentRef>,
    private val quotes: Map<FragmentRef, SentenceQuote>,
    private val scope: CoroutineScope,
    initialSpeed: AutoScrollSpeed = AutoScrollSpeed.Default,
    private val onExhausted: () -> Unit = {},
) : SentenceTicker {

    private val _currentFragment = MutableStateFlow<FragmentRef?>(null)
    override val currentFragment: StateFlow<FragmentRef?> = _currentFragment.asStateFlow()

    private val _progress = MutableStateFlow<Double?>(null)
    override val progress: StateFlow<Double?> = _progress.asStateFlow()

    @Volatile
    private var speed: AutoScrollSpeed = initialSpeed

    private var tickerJob: Job? = null

    /** Update WPM live. Change takes effect on the next sentence — current dwell continues. */
    fun setSpeed(newSpeed: AutoScrollSpeed) {
        speed = newSpeed
    }

    override fun play() {
        if (tickerJob?.isActive == true) return
        if (orderedFragments.isEmpty()) {
            onExhausted()
            return
        }
        // If nothing is set yet, or the current fragment isn't in the list, start at index 0.
        val startIndex = _currentFragment.value
            ?.let { orderedFragments.indexOf(it).takeIf { i -> i >= 0 } }
            ?: 0
        _currentFragment.value = orderedFragments[startIndex]
        _progress.value = 0.0
        tickerJob = scope.launch { runTicker(startIndex) }
    }

    override fun pause() {
        tickerJob?.cancel()
        tickerJob = null
    }

    override fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        _currentFragment.value = null
        _progress.value = null
    }

    override fun goTo(fragment: FragmentRef) {
        val idx = orderedFragments.indexOf(fragment)
        if (idx < 0) return
        _currentFragment.value = fragment
        _progress.value = 0.0
        val wasRunning = tickerJob?.isActive == true
        tickerJob?.cancel()
        if (wasRunning) {
            tickerJob = scope.launch { runTicker(idx) }
        }
    }

    private suspend fun runTicker(startIndex: Int) {
        var i = startIndex
        while (currentCoroutineContext().isActive && i < orderedFragments.size) {
            val ref = orderedFragments[i]
            _currentFragment.value = ref
            val dwellMs = dwellMillisFor(ref)
            val stepMs = (dwellMs / PROGRESS_STEPS).coerceAtLeast(MIN_STEP_MS)
            var elapsed = 0L
            _progress.value = 0.0
            while (elapsed < dwellMs && currentCoroutineContext().isActive) {
                val step = minOf(stepMs, dwellMs - elapsed)
                delay(step)
                elapsed += step
                _progress.value = (elapsed.toDouble() / dwellMs).coerceIn(0.0, 1.0)
            }
            i++
        }
        if (currentCoroutineContext().isActive && i >= orderedFragments.size) {
            _progress.value = 1.0
            onExhausted()
        }
    }

    private fun dwellMillisFor(ref: FragmentRef): Long {
        val quote = quotes[ref]
        val words = wordCount(quote?.highlight ?: "")
        val perWordMs = 60_000.0 / speed.wpm
        return (words * perWordMs).toLong().coerceAtLeast(MIN_DWELL_MS)
    }

    companion object {
        // Sentences shorter than a handful of words would otherwise flash by; clamp so the eye can
        // actually register the highlight before it moves on.
        internal const val MIN_DWELL_MS: Long = 400L
        // Progress updates ~5×/sentence give paginated mid-sentence page turns something to hook on
        // without spamming state emissions on very long dwells.
        private const val PROGRESS_STEPS: Int = 5
        private const val MIN_STEP_MS: Long = 50L

        /** Whitespace-split word count. Matches Auto-Scroll's "N words per minute" convention. */
        fun wordCount(text: String): Int {
            if (text.isBlank()) return 1
            var count = 0
            var inWord = false
            for (c in text) {
                if (c.isWhitespace()) {
                    if (inWord) count++
                    inWord = false
                } else {
                    inWord = true
                }
            }
            if (inWord) count++
            return count.coerceAtLeast(1)
        }
    }
}
