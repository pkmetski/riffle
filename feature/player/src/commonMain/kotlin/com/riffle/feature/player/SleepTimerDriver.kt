package com.riffle.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the sleep-timer countdown and fade-out, shared between [AudiobookController] (Android)
 * and [IosAudioPlayerController] (iOS). Both contained identical countdown loops and fade-out
 * coordination; this class is the single shared implementation.
 *
 * @param scope The coroutine scope that owns the timer job. Caller-owned — the driver cancels all
 *   its own jobs but does not cancel the scope.
 * @param fadeAndStop Platform-specific fade-to-silence + pause action, called when the countdown
 *   expires or [triggerNow] is called. Must be idempotent (may be called from concurrent jobs if
 *   [triggerNow] races a countdown expiry).
 */
class SleepTimerDriver(
    private val scope: CoroutineScope,
    private val fadeAndStop: suspend () -> Unit,
) {
    private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
    val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

    private val _fired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fired: SharedFlow<Unit> = _fired.asSharedFlow()

    private var job: Job? = null

    fun set(mode: SleepTimerMode) {
        job?.cancel()
        job = null
        _sleepTimer.value = mode
        when (mode) {
            is SleepTimerMode.CountDown -> {
                var remaining = mode.remainingMs
                job = scope.launch {
                    while (remaining > 0L) {
                        _sleepTimer.value = SleepTimerMode.CountDown(remaining)
                        delay(TICK_MS)
                        remaining -= TICK_MS
                    }
                    runFadeAndStop()
                }
            }
            is SleepTimerMode.EndOfChapter -> { /* chapter detection is done in the ViewModel */ }
            is SleepTimerMode.None -> { /* already cleared above */ }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _sleepTimer.value = SleepTimerMode.None
    }

    fun triggerNow() {
        job?.cancel()
        job = scope.launch { runFadeAndStop() }
    }

    private suspend fun runFadeAndStop() {
        fadeAndStop()
        _sleepTimer.value = SleepTimerMode.None
        _fired.tryEmit(Unit)
    }

    companion object {
        internal const val TICK_MS = 1_000L
        const val FADE_STEPS = 50
        const val FADE_STEP_MS = 100L
    }
}
