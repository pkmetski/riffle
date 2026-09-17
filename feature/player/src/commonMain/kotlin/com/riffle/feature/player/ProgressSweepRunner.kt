package com.riffle.feature.player

/** Single-method seam for running the durable dirty-position sweep. iOS no-op; Android wraps [com.riffle.core.sync.ProgressSweep]. */
fun interface ProgressSweepRunner {
    suspend fun run()

    companion object {
        val NOOP = ProgressSweepRunner { }
    }
}
