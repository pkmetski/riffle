@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.riffle.core.common

import platform.posix.time

object IosSystemClock : Clock {
    // POSIX time() gives whole seconds since Jan 1 1970.
    override fun nowMs(): Long = time(null) * 1_000L

    // nowNs() is used only for Readium performance traces (ADR 0039) — not exercised on iOS.
    override fun nowNs(): Long = time(null) * 1_000_000_000L
}
