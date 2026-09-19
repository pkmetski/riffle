package com.riffle.feature.library

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Suspends until the host reports a *genuine* resumed state.
 *
 * Navigating away from HOME as a new root pops back to HOME first and only then pushes the
 * library destination on top. That pop momentarily promotes HOME to RESUMED before the
 * subsequent navigate() demotes it again. Waiting on a bare "resumed" signal fires during that
 * transient window, waking [navigateFromHome] a second time → a flash of the HOME spinner.
 *
 * So: after each resumed signal, yield once to let the synchronous navigate() call complete,
 * then re-check. If the host fell back below RESUMED the loop repeats and waits for the next
 * signal — which only arrives when HOME is genuinely the foreground destination.
 *
 * Both lifecycle interactions are lambdas so tests can drive the transitions without a real
 * platform lifecycle object (and without the main-dispatcher dependency it brings).
 */
suspend fun awaitGenuinelyResumedWith(
    waitForResumed: suspend () -> Unit,
    isStillResumed: () -> Boolean,
) {
    while (true) {
        waitForResumed()
        yield()
        if (isStillResumed()) break
    }
}

/**
 * Resolves the start destination for HOME and hands it to [onDestination] on the main
 * dispatcher.
 *
 * Navigation hosts keep the previous back-stack entry alive for predictive-back animations, so
 * HOME can be merely STARTED while the library destination is the foreground. [awaitResumed]
 * (backed by [awaitGenuinelyResumedWith] in production) suspends until HOME is genuinely the
 * foreground destination. In tests it is a plain suspend lambda so the test controls exactly
 * when navigation is unblocked.
 */
suspend fun navigateFromHome(
    awaitResumed: suspend () -> Unit,
    viewModel: HomeViewModel,
    onDestination: suspend (HomeViewModel.StartDestination) -> Unit,
) {
    awaitResumed()
    val dest = viewModel.getStartDestination()
    withContext(viewModel.dispatchers.mainImmediate) {
        onDestination(dest)
    }
}
