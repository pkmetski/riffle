package com.riffle.core.domain.cadence

/**
 * Mutual-exclusion arbiter for the reader's three "keep advancing on your own" features:
 * Auto-Scroll, Cadence, and Readaloud. Only one may be [Feature.None]-≠, and starting one pauses
 * the others.
 *
 * The reader ViewModel calls [onStart] before dispatching a Start event to Feature X's own reducer,
 * receives back the "pause-the-others" fan-out, and issues those pauses. Symmetric on stop.
 *
 * Pure — no state stored here; the caller is the source of truth and passes in the current
 * running-feature (if any). This keeps the arbiter trivially testable and lets the caller reason
 * about the transition in one place instead of racing three feature-local reducers.
 */
enum class Feature { None, AutoScroll, Cadence, Readaloud }

data class ArbiterAction(
    val pauseAutoScroll: Boolean = false,
    val pauseCadence: Boolean = false,
    val pauseReadaloud: Boolean = false,
) {
    val isNoop: Boolean get() = !pauseAutoScroll && !pauseCadence && !pauseReadaloud
    companion object { val Noop = ArbiterAction() }
}

/**
 * Compute which currently-running features to pause when [starting] is about to activate.
 * Returns [ArbiterAction.Noop] when [starting] is [Feature.None] or the current state already
 * has that feature active.
 */
/**
 * Which feature counts as running, given each one's live state.
 *
 * The at-most-one invariant is guaranteed by the arbiter itself — the last successful start would
 * have parked any prior feature. In the event of a race the highest-priority winner is picked
 * (Cadence > AutoScroll > Readaloud) so the pause fan-out is deterministic.
 */
fun currentRunningFeature(
    cadenceRunning: Boolean,
    autoScrollRunning: Boolean,
    readaloudPlaying: Boolean,
): Feature = when {
    cadenceRunning -> Feature.Cadence
    autoScrollRunning -> Feature.AutoScroll
    readaloudPlaying -> Feature.Readaloud
    else -> Feature.None
}

/**
 * The cause Cadence's pause should carry when [starting] parks it. Cadence keeps the cause so a
 * scoped resume can tell "the user paused me from the pill" from "auto-scroll took over".
 */
fun cadencePauseCauseFor(starting: Feature): PauseCause = when (starting) {
    Feature.AutoScroll -> PauseCause.AutoScrollStarted
    Feature.Readaloud -> PauseCause.ReadaloudStarted
    else -> PauseCause.PanelOpen
}

/**
 * Apply [onStart]'s fan-out. Both readers call this immediately before dispatching a Start to
 * [starting]'s own controller, so "starting X parks Y" is one decision in one place rather than
 * a per-host `if` ladder that can drift — the reason iOS gets mutual exclusion for free the
 * moment its auto-scroll and Cadence both exist.
 *
 * A host that does not have one of the features leaves its handler at the default no-op.
 */
fun runArbiter(
    currentRunning: Feature,
    starting: Feature,
    stopAutoScroll: () -> Unit = {},
    pauseCadence: (PauseCause) -> Unit = {},
    pauseReadaloud: () -> Unit = {},
) {
    val action = onStart(currentRunning, starting)
    if (action.pauseAutoScroll) stopAutoScroll()
    if (action.pauseReadaloud) pauseReadaloud()
    if (action.pauseCadence) pauseCadence(cadencePauseCauseFor(starting))
}

fun onStart(currentRunning: Feature, starting: Feature): ArbiterAction = when (starting) {
    Feature.None -> ArbiterAction.Noop
    currentRunning -> ArbiterAction.Noop
    Feature.AutoScroll -> ArbiterAction(
        pauseCadence = currentRunning == Feature.Cadence,
        pauseReadaloud = currentRunning == Feature.Readaloud,
    )
    Feature.Cadence -> ArbiterAction(
        pauseAutoScroll = currentRunning == Feature.AutoScroll,
        pauseReadaloud = currentRunning == Feature.Readaloud,
    )
    Feature.Readaloud -> ArbiterAction(
        pauseAutoScroll = currentRunning == Feature.AutoScroll,
        pauseCadence = currentRunning == Feature.Cadence,
    )
}
