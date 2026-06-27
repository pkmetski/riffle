package com.riffle.app.feature.reader.autoscroll

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
import kotlinx.coroutines.Dispatchers
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates an Auto-Scroll session: holds [state], emits pixel scroll deltas via [scrollDeltas],
 * and forwards lifecycle/UI events into the pure [reduce] state machine. Production uses
 * [Dispatchers.Main.immediate]; tests use [forTest] to inject a [StandardTestDispatcher].
 */
@Singleton
open class AutoScrollController internal constructor(
    dispatcher: CoroutineDispatcher,
) {
    @Inject constructor() : this(Dispatchers.Main.immediate)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow<AutoScrollState>(AutoScrollState.Idle)
    val state: StateFlow<AutoScrollState> = _state.asStateFlow()

    private val _scrollDeltas = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val scrollDeltas: SharedFlow<Int> = _scrollDeltas.asSharedFlow()

    private var defaultSpeed: AutoScrollSpeed = AutoScrollSpeed.Default
    private var layoutContext: () -> LayoutContext =
        { LayoutContext(wordsPerLine = 9f, lineHeightPx = 28f) }
    private var now: () -> Long = { System.nanoTime() }

    private val accumulator = ScrollDeltaAccumulator()
    private var tickerJob: Job? = null

    fun setDefaultSpeed(speed: AutoScrollSpeed) {
        defaultSpeed = speed
    }

    fun setLayoutContext(supplier: () -> LayoutContext) {
        layoutContext = supplier
    }

    internal fun setClock(clock: () -> Long) {
        now = clock
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
            var lastNanos = now()
            while (isActive) {
                delay(FRAME_INTERVAL_MS)
                val n = now()
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

        internal fun forTest(dispatcher: CoroutineDispatcher): AutoScrollController =
            AutoScrollController(dispatcher)
    }
}
