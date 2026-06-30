package com.riffle.core.domain

/**
 * Local mirror of `core:domain`'s `TestClock` — `core:domain`'s `src/test` source set is not on
 * `core:data`'s test classpath, so the same fake has to live here under the same FQN. Keep this
 * in sync with `core/domain/src/test/.../TestClock.kt`.
 */
class TestClock(initialMs: Long = 0L) : Clock {
    private var ms: Long = initialMs

    override fun nowMs(): Long = ms
    override fun nowNs(): Long = ms * 1_000_000L

    fun setNowMs(ms: Long) { this.ms = ms }
    fun advance(ms: Long) { this.ms += ms }
}
