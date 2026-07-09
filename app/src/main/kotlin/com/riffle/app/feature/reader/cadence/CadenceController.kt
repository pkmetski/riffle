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

    /**
     * Progress fraction (0..1) of the current fragment's dwell — mirrors [WpmTicker.progress].
     * Reader-screen intra-column follow uses this to drive the same page-turn-mid-sentence
     * behaviour Readaloud gets from audio-clock timing. Null when idle or between fragments.
     */
    private val _currentProgress = MutableStateFlow<Double?>(null)
    val currentProgress: StateFlow<Double?> = _currentProgress.asStateFlow()

    private var defaultSpeed: AutoScrollSpeed = AutoScrollSpeed.Default
    private var source: SentenceSource? = null
    private var ticker: WpmTicker? = null
    private var forwarderJob: Job? = null

    fun setDefaultSpeed(speed: AutoScrollSpeed) {
        defaultSpeed = speed
        ticker?.setSpeed(defaultSpeed)
    }

    /**
     * Bind the current chapter's [SentenceSource]. Rebinding replaces the previous chapter's
     * ticker — the state ([Running] / [Paused] / [Idle]) is preserved so an auto-advance to the
     * next chapter can resume ticking without a user tap. If state was [Running] at bind-time the
     * new ticker starts immediately; otherwise it stays idle until [dispatch]([CadenceEvent.Start]).
     *
     * [onExhausted] fires when the ticker drains this source's ordering — Cadence's caller uses
     * this to trigger the chapter-forward navigation. The state does NOT flip to Idle here (that
     * used to happen inside the reducer's `ReachedEndOfBook` branch); the caller navigates first,
     * then a new `bind` installs the next chapter's ticker and the [Running] state carries over.
     */
    open suspend fun bind(source: SentenceSource, onExhausted: () -> Unit = {}) {
        val wasRunning = _state.value is CadenceState.Running
        // Preserve the currently-highlighted fragment across the rebind. bind() fires on every
        // chapter tokenise (each Readium page load / continuous sliding-window entry / any
        // reflow that re-invokes CadenceDomScript). Nuking _currentFragment before re-creating
        // the ticker meant that if Cadence was running, `t.play()` fell back to
        // `orderedFragments[0]` (the very first cd of the very first chapter tokenised this
        // session) — user-visible symptom: "switching between reading modes restarts Cadence
        // from the wrong location". Instead we snapshot the pre-bind position and hand it back
        // to the fresh ticker via goTo — the merged fragment list is a superset of the old, so
        // the old ref stays valid.
        val previousFragment = _currentFragment.value
        // Tear down previous ticker without touching state — the state carries user intent.
        ticker?.stop()
        forwarderJob?.cancel()

        this.source = source
        val quotes = source.loadAll()
        val order = quotes.keys.toList()
        val t = WpmTicker(
            orderedFragments = order,
            quotes = quotes,
            scope = scope,
            initialSpeed = _state.value.speedOrNull ?: defaultSpeed,
            onExhausted = onExhausted,
        )
        ticker = t
        // Restore position BEFORE hooking the forwarder so the reset _currentFragment doesn't
        // briefly go to null in downstream collectors (would clear the highlight for a frame).
        if (previousFragment != null && previousFragment in order) t.goTo(previousFragment)
        forwarderJob = scope.launch {
            launch { t.currentFragment.collect { _currentFragment.value = it } }
            launch { t.progress.collect { _currentProgress.value = it } }
        }
        if (wasRunning) t.play()
    }

    /**
     * Tear down the current session entirely — cancels the ticker + resets state + drops the
     * source. Used when the reader closes.
     */
    fun unbind() {
        ticker?.stop()
        ticker = null
        forwarderJob?.cancel()
        forwarderJob = null
        source = null
        _currentFragment.value = null
        _currentProgress.value = null
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

    /**
     * Scoped pause/resume — mirrors `FormattingSession.setAutoScrollPaused`.
     * `paused = true` dispatches [CadenceEvent.Pause] with [cause].
     * `paused = false` only dispatches [CadenceEvent.Resume] when the current pause cause matches
     * [cause], so a short-lived cause (e.g. [PauseCause.TextSelection]) can't silently un-park a
     * longer-lived one (e.g. [PauseCause.PanelOpen]) that started while the selection was still
     * active. Called from the reader ViewModel.
     */
    fun setPaused(paused: Boolean, cause: PauseCause) {
        if (paused) {
            dispatch(CadenceEvent.Pause(cause))
        } else {
            val current = _state.value
            if (current is CadenceState.Paused && current.cause == cause) {
                dispatch(CadenceEvent.Resume)
            }
        }
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
