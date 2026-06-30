package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Application-lifetime coroutine seam for work that must outlive any caller's scope — terminal
 * progress flushes, close-path PATCHes, one-shot migrations kicked off in `Application.onCreate`.
 *
 * The hazard: launching a final write on `viewModelScope` (or any per-screen scope) loses it the
 * instant the screen goes away; the user closes the player, navigates back, and the in-flight
 * `viewModelScope` is cancelled before the PATCH round-trip completes. ProgressFlushScope was the
 * tactical fix for the audiobook close-path; this seam generalises the contract so every "must
 * survive teardown" write routes through one named owner.
 *
 * Scope lifetime: cancels only on process death.
 */
interface ApplicationScope {
    /**
     * Raw survivable [CoroutineScope] handle — for sites that need to pass a scope into a
     * scope-consuming API (`stateIn`, `shareIn`, a worker class that takes a `CoroutineScope`
     * constructor param). Prefer [launchSurvivable] / [withSurvivable] for one-shot work.
     *
     * Never call `.cancel()` on this — it would tear down every survivable launch in the app.
     * Per-component cancellable scopes belong on their own [Job], not here (the "in-screen work"
     * exclusion in the seam's contract).
     */
    val coroutineScope: CoroutineScope

    /** Launch [block] on the survivable scope; returns the [Job] for callers that want to await it. */
    fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job

    /**
     * Run [block] on the survivable scope and return its result. The work proceeds to completion
     * even if the caller's coroutine is cancelled — useful for a UI scope that wants to await a
     * terminal write before navigating, without losing the write if the user backs out mid-await.
     */
    suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T
}
