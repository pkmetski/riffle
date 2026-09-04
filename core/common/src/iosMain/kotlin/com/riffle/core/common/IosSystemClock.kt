package com.riffle.core.common

import platform.Foundation.NSDate

object IosSystemClock : Clock {
    override fun nowMs(): Long = (NSDate.date().timeIntervalSince1970() * 1_000).toLong()

    // nowNs() is used only for Readium performance traces (ADR 0039) — not exercised on iOS.
    // Wall-clock nanoseconds are sufficient as a non-monotonic approximation here.
    override fun nowNs(): Long = (NSDate.date().timeIntervalSince1970() * 1_000_000_000).toLong()
}
