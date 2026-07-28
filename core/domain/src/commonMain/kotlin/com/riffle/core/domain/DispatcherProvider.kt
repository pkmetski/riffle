package com.riffle.core.domain

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Typed seam for coroutine dispatchers — replaces direct [kotlinx.coroutines.Dispatchers.IO] /
 * `Main` / `Default` references so tests can swap in a `StandardTestDispatcher` (or any test
 * dispatcher) without resorting to `Dispatchers.setMain` / global hacks.
 *
 * Production binding is [DefaultDispatcherProvider]; tests inject a `TestDispatcherProvider`
 * backed by `kotlinx-coroutines-test` dispatchers. The four properties exhaust the dispatcher
 * surface this codebase uses; if a fifth is ever needed (e.g. a custom limited-parallelism
 * dispatcher), add it here rather than reintroducing direct `Dispatchers.X` literals.
 */
interface DispatcherProvider {
    /** UI thread — default-binds to `Dispatchers.Main`. */
    val main: CoroutineDispatcher

    /**
     * Immediate-mode UI dispatcher — default-binds to `Dispatchers.Main.immediate`. Use for sites
     * that must avoid the post-to-main hop (Media3 controller construction, JS message receivers).
     */
    val mainImmediate: CoroutineDispatcher

    /** IO-bound work (disk, network, DataStore) — default-binds to `Dispatchers.IO`. */
    val io: CoroutineDispatcher

    /** CPU-bound work — default-binds to `Dispatchers.Default`. */
    val default: CoroutineDispatcher
}
