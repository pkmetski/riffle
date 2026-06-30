package com.riffle.app.feature.reader

import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a final progress write (the close / pause flush) on an application-lifetime scope so it isn't
 * cancelled mid-network-write when the user leaves the screen the instant they trigger it.
 *
 * Now a thin adapter over [ApplicationScope] — the seam every "must outlive teardown" write should
 * route through. Existing callers keep using `flush { ... }`; new code can take [ApplicationScope]
 * directly and call [ApplicationScope.launchSurvivable] / [ApplicationScope.withSurvivable].
 *
 * The hazard this exists for: a reader / audiobook ViewModel launching its close flush on
 * `viewModelScope` loses the in-flight ABS PATCH the moment the ViewModel is cleared — which on the
 * audiobook player's `onCleared` is *always* (the scope is already cancelled by then), and on the
 * reader's readaloud-close is a race lost whenever the user backs out of the book before the PATCH
 * round-trip finishes ("close, then leave right away"). In-session periodic syncs stay on
 * `viewModelScope` — they *should* stop when the screen goes away; only the terminal flush comes here.
 */
@Singleton
class ProgressFlushScope @Inject constructor(
    private val applicationScope: ApplicationScope,
) {
    /** Launch [write] on the survivable scope; returns its [Job] (for tests / awaiting if needed). */
    fun flush(write: suspend () -> Unit): Job = applicationScope.launchSurvivable { write() }
}
