package com.riffle.feature.reader.autoscroll

import com.riffle.core.common.Clock
import com.riffle.core.common.platformSystemClock
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.LayoutContext
import com.riffle.core.domain.autoscroll.ScrollDeltaAccumulator
import com.riffle.core.domain.autoscroll.isActive
import com.riffle.core.domain.autoscroll.pxPerSecond
import com.riffle.core.domain.autoscroll.reduce
import com.riffle.core.domain.autoscroll.speedOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates an Auto-Scroll session: holds [state], emits pixel scroll deltas via [scrollDeltas],
 * and forwards lifecycle/UI events into the pure [reduce] state machine. Production uses
 * `DispatcherProvider.mainImmediate`; tests use [forTest] to inject a [StandardTestDispatcher].
 */

open class AutoScrollController internal constructor(
    dispatcher: CoroutineDispatcher,
    private var clock: Clock = platformSystemClock,
) {
    constructor(dispatchers: DispatcherProvider) : this(dispatchers.mainImmediate, platformSystemClock)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow<AutoScrollState>(AutoScrollState.Idle)
    val state: StateFlow<AutoScrollState> = _state.asStateFlow()

    private val _scrollDeltas = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val scrollDeltas: SharedFlow<Int> = _scrollDeltas.asSharedFlow()

    private var defaultSpeed: AutoScrollSpeed = AutoScrollSpeed.Default

    // Default layout assumes a typical xxhdpi Android phone reading body text at the project's
    // default font size: ~22 CSS px line height × density 3 ≈ 66 device pixels per line, and
    // about 9 words on a 411dp-wide page. The reader screen overrides this at runtime via
    // [setLayoutContext] so the live pace stays correct when the user bumps font size or rotates.
    private var layoutContext: () -> LayoutContext =
        { LayoutContext(wordsPerLine = 9f, lineHeightPx = 66f) }

    private val accumulator = ScrollDeltaAccumulator()
    private var tickerJob: Job? = null

    fun setDefaultSpeed(speed: AutoScrollSpeed) {
        defaultSpeed = speed
    }

    fun setLayoutContext(supplier: () -> LayoutContext) {
        layoutContext = supplier
    }

    internal fun setClock(clock: Clock) {
        this.clock = clock
    }

    fun dispatch(event: AutoScrollEvent) {
        val prev = _state.value
        val next = reduce(prev, event, defaultSpeed)
        if (prev === next) return
        _state.value = next
        if (next.isActive && tickerJob == null) {
            startTicker()
        } else if (!next.isActive) {
            stopTicker()
        }
    }

    private fun startTicker() {
        accumulator.reset()
        tickerJob = scope.launch {
            var lastNanos = clock.nowNs()
            while (isActive) {
                delay(FRAME_INTERVAL_MS)
                val n = clock.nowNs()
                val dtSec = ((n - lastNanos).coerceAtLeast(0L)) / 1_000_000_000f
                lastNanos = n
                val speed = _state.value.speedOrNull ?: continue
                val px = accumulator.advance(dtSec, pxPerSecond(speed, layoutContext()))
                if (px > 0) _scrollDeltas.tryEmit(px)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun release() {
        stopTicker()
        scope.cancel()
    }

    companion object {
        const val FRAME_INTERVAL_MS: Long = 16L

        /**
         * A controller driven by a test dispatcher. Public rather than `internal` because the
         * controller now lives in `:feature:reader` while `:app`'s `FormattingSessionTest` and
         * `PdfReaderViewModelFormattingTest` still construct one.
         */
        fun forTest(dispatcher: CoroutineDispatcher): AutoScrollController =
            AutoScrollController(dispatcher)
    }
}

/**
 * Nudge the live session's speed and report the wpm that should be persisted.
 *
 * Returns null when there is nothing to persist: no active session (so the nudge had no speed to
 * act on), or the stored preference already holds the new value. Both hosts call this so a HUD
 * nudge survives the reader identically — Android through `FormattingSession.nudgeAutoScroll`,
 * iOS straight from the pill.
 */
fun AutoScrollController.nudgeSpeedAndPersistableWpm(by: Int, storedWpm: Int): Int? {
    dispatch(AutoScrollEvent.NudgeSpeed(by))
    val newWpm = state.value.speedOrNull?.wpm ?: return null
    return newWpm.takeIf { it != storedWpm }
}
