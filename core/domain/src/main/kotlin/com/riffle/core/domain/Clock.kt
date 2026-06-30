package com.riffle.core.domain

/**
 * Typed seam for "what time is it now?" — replaces direct [System.currentTimeMillis] / [System.nanoTime]
 * calls so session-duration, handoff-trace, autoscroll-tick and similar timing logic become
 * deterministic in tests.
 *
 * Production binding is [SystemClock]; tests inject a controllable fake (`TestClock`) from
 * `core/domain` test source. The contract is intentionally narrow — only the `now()` family. For
 * hour-of-day scheduling (ADR 0022) see `TimeProvider`, which delegates to this seam for millis.
 */
interface Clock {
    /** Wall-clock millis since epoch — for session timestamps, ages, scheduling deadlines. */
    fun nowMs(): Long

    /** Monotonic nanos — for sub-ms handoff traces (ADR 0032) and short performance probes. */
    fun nowNs(): Long
}
