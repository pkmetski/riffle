package com.riffle.core.domain

/**
 * Lets reader VMs and [com.riffle.core.domain.appearance.AppearanceCoordinator] read "now" in a
 * way tests can substitute. Production binding is a platform [SystemTimeProvider]; tests inject
 * a fake. Distinct from `Clock` (millis/nanos) — TimeProvider is intentionally narrow to
 * hour-of-day scheduling (ADR 0026), so callers don't have to deal with time-zone conversions.
 */
interface TimeProvider {
    fun nowLocalTime(): LocalMinuteTime
}
