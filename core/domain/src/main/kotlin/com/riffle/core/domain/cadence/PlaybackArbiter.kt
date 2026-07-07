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
