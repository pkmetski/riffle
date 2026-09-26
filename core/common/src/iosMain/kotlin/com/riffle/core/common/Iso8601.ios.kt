package com.riffle.core.common

import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatOptions
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import kotlin.math.roundToLong

private fun formatter(options: NSISO8601DateFormatOptions): NSISO8601DateFormatter =
    NSISO8601DateFormatter().apply { formatOptions = options }

private val withFraction: NSISO8601DateFormatOptions =
    NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
private val withoutFraction: NSISO8601DateFormatOptions = NSISO8601DateFormatWithInternetDateTime

actual fun formatIso8601(epochMillis: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    // Mirror java.time.Instant.toString(): fractional seconds only when non-zero.
    val options = if (epochMillis % 1000L == 0L) withoutFraction else withFraction
    return formatter(options).stringFromDate(date)
}

actual fun parseIso8601ToEpochMillis(iso: String): Long? {
    // NSISO8601DateFormatter is strict about the fractional-seconds option: with it set the
    // formatter rejects "…:45Z", without it "…:45.123Z". Try both so either shape round-trips.
    val date = formatter(withFraction).dateFromString(iso)
        ?: formatter(withoutFraction).dateFromString(iso)
        ?: return null
    return (date.timeIntervalSince1970 * 1000.0).roundToLong()
}
