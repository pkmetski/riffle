package com.riffle.app.feature.reader.cadence

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.cadence.CadenceEvent
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.PauseCause
import com.riffle.core.domain.cadence.isActive
import com.riffle.core.domain.cadence.reduce
import com.riffle.core.domain.cadence.speedOrNull
import com.riffle.core.domain.sentence.FragmentRef
import com.riffle.core.domain.sentence.SentenceSource
import com.riffle.core.domain.sentence.WpmTicker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates a Cadence session — parallel to `AutoScrollController`. Owns:
 *  - [state]: the pure `CadenceState` (Idle/Running/Paused).
 *  - [currentFragment]: fragment currently under the Cadence highlight, from the [WpmTicker].
 *
 * The reader ViewModel binds a book's [SentenceSource] (which is a [DomSentenceSource] in
 * production; other implementations are fine for tests) via [bind]; that stores an ordered
 * fragment list and hands it to [WpmTicker] so the ticker's per-sentence dwell reflects the
 * live DOM tokenisation. `unbind` tears the session down when the reader closes.
 *
 * Mutual exclusion with Readaloud + Auto-Scroll is enforced by the caller via
 * [com.riffle.core.domain.cadence.onStart]; the caller issues `Pause(ReadaloudStarted)` /
 * `Pause(AutoScrollStarted)` here at the seam so the reducer's pause state carries the cause.
 */
@Singleton
open class CadenceController internal constructor(
    dispatcher: CoroutineDispatcher,
) {
    @Inject constructor(dispatchers: DispatcherProvider) : this(dispatchers.mainImmediate)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow<CadenceState>(CadenceState.Idle)
    val state: StateFlow<CadenceState> = _state.asStateFlow()

    private val _currentFragment = MutableStateFlow<FragmentRef?>(null)
    val currentFragment: StateFlow<FragmentRef?> = _currentFragment.asStateFlow()

    private var defaultSpeed: AutoScrollSpeed = AutoScrollSpeed.Default
    private var source: SentenceSource? = null
    private var ticker: WpmTicker? = null
    private var forwarderJob: Job? = null

    fun setDefaultSpeed(speed: AutoScrollSpeed) {
        defaultSpeed = speed
        ticker?.setSpeed(defaultSpeed)
    }

    /**
     * Bind the current book's [SentenceSource]. Calling [dispatch]([CadenceEvent.Start]) before
     * [bind] is a no-op; calling it after builds a [WpmTicker] over the source's fragment
     * ordering and starts ticking. Re-binding replaces the ticker.
     */
    open suspend fun bind(source: SentenceSource, onEndOfBook: () -> Unit = {}) {
        unbind()
        this.source = source
        val quotes = source.loadAll()
        val order = quotes.keys.toList()
        val t = WpmTicker(
            orderedFragments = order,
            quotes = quotes,
            scope = scope,
            initialSpeed = defaultSpeed,
            onExhausted = {
                _state.value = reduce(_state.value, CadenceEvent.ReachedEndOfBook, defaultSpeed)
                onEndOfBook()
            },
        )
        ticker = t
        forwarderJob = scope.launch {
            t.currentFragment.collect { _currentFragment.value = it }
        }
    }

    fun unbind() {
        ticker?.stop()
        ticker = null
        forwarderJob?.cancel()
        forwarderJob = null
        source = null
        _currentFragment.value = null
        _state.value = CadenceState.Idle
    }

    /**
     * Forward an event through the reducer AND apply it to the ticker. State + ticker stay in
     * lock-step: [CadenceEvent.Start] → ticker.play(); [CadenceEvent.Pause]/[CadenceEvent.Stop] →
     * ticker.pause()/stop(); [CadenceEvent.NudgeSpeed] → live speed change on the ticker.
     */
    fun dispatch(event: CadenceEvent) {
        val prev = _state.value
        val next = reduce(prev, event, defaultSpeed)
        if (prev === next && event !is CadenceEvent.NudgeSpeed) {
            // NudgeSpeed while Running is a state-change we still want to forward to the ticker's
            // live speed knob — the reducer returns a new state with the nudged speed, so this
            // early-out doesn't actually fire for NudgeSpeed. Guarded explicitly to make that clear.
            return
        }
        _state.value = next
        val t = ticker
        when (event) {
            CadenceEvent.Start, CadenceEvent.Resume -> if (next.isActive) t?.play()
            CadenceEvent.Stop, CadenceEvent.ReachedEndOfBook -> t?.stop()
            is CadenceEvent.Pause -> t?.pause()
            is CadenceEvent.NudgeSpeed -> next.speedOrNull?.let { t?.setSpeed(it) }
        }
    }

    /**
     * Convenience alias for the arbiter's fan-out: "Readaloud just started → pause Cadence." The
     * ticker retains its position; a later [CadenceEvent.Resume] picks up where it left off.
     */
    fun pauseFor(cause: PauseCause) {
        dispatch(CadenceEvent.Pause(cause))
    }

    /** Called on the ticker itself, once a fragment lookup succeeds and we can position at it. */
    fun goTo(fragment: FragmentRef) {
        ticker?.goTo(fragment)
    }

    fun release() {
        unbind()
        scope.cancel()
    }
}
