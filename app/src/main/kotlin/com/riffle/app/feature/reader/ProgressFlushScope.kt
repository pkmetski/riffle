package com.riffle.app.feature.reader

import com.riffle.app.di.ProgressFlush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a final progress write (the close / pause flush) on an application-lifetime scope so it isn't
 * cancelled mid-network-write when the user leaves the screen the instant they trigger it.
 *
 * The hazard this exists for: a reader / audiobook ViewModel launching its close flush on
 * `viewModelScope` loses the in-flight ABS PATCH the moment the ViewModel is cleared — which on the
 * audiobook player's `onCleared` is *always* (the scope is already cancelled by then), and on the
 * reader's readaloud-close is a race lost whenever the user backs out of the book before the PATCH
 * round-trip finishes ("close, then leave right away"). In-session periodic syncs stay on
 * `viewModelScope` — they *should* stop when the screen goes away; only the terminal flush comes here.
 *
 * Mirrors the existing [ProgressFlush]/download scope rationale in `AppModule`.
 */
@Singleton
class ProgressFlushScope @Inject constructor(
    @ProgressFlush private val scope: CoroutineScope,
) {
    /** Launch [write] on the survivable scope; returns its [Job] (for tests / awaiting if needed). */
    fun flush(write: suspend () -> Unit): Job = scope.launch { write() }
}
