package com.riffle.core.domain

import java.time.LocalTime

/**
 * Lets reader VMs and the [com.riffle.core.domain.appearance.AppearanceCoordinator] read "now"
 * in a way tests can substitute. Production binding is [SystemTimeProvider]; tests inject a fake.
 *
 * Distinct from [Clock] (millis/nanos) — TimeProvider is intentionally narrow to hour-of-day
 * scheduling (ADR 0022), so callers don't have to deal with `ZoneId` conversions.
 */
interface TimeProvider {
    fun nowLocalTime(): LocalTime
}

/** Production [TimeProvider]. Bound in `AppModule`. */
object SystemTimeProvider : TimeProvider {
    override fun nowLocalTime(): LocalTime = LocalTime.now()
}
