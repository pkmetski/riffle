package com.riffle.core.domain

import com.riffle.core.common.Clock

/**
 * Controllable [Clock] for tests — start at any epoch instant, advance by [advance] or set
 * absolutely via [setNowMs]. `nowNs()` advances in lock-step with millis (1ms = 1_000_000ns).
 */
class TestClock(initialMs: Long = 0L) : Clock {
    private var ms: Long = initialMs

    override fun nowMs(): Long = ms
    override fun nowNs(): Long = ms * 1_000_000L

    fun setNowMs(ms: Long) { this.ms = ms }
    fun advance(ms: Long) { this.ms += ms }
}
