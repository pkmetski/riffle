package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * Runs [block] on every offline→online transition of a boolean connectivity flow.
 *
 * Intended for use with [ConnectivityObserver.isOnline] (a validated flow — see PR #402's
 * `ValidatedNetworkTracker`), where consumers want to re-drive work the moment connectivity is
 * genuinely restored. On [kotlinx.coroutines.flow.StateFlow] the first emission is the current
 * value: dropping it and filtering to `true` yields exactly the false→true edges. StateFlow's
 * built-in same-value dedup means redundant `true` emissions do not re-fire [block].
 *
 * Shared by the library-view auto-refresh listener and the WebDAV sweep kicker so the edge
 * semantics live in exactly one place.
 */
suspend fun Flow<Boolean>.collectReconnects(block: suspend () -> Unit) {
    drop(1).filter { it }.collect { block() }
}
