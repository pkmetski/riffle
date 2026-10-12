package com.riffle.core.catalog.komga

import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.timeIntervalSince1970

// Options that cover both Komga's full-precision form (2023-01-15T10:30:00.000Z) and the
// shorter form without fractional seconds (2023-01-15T10:30:00Z).
private val WITH_FRACTIONAL = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds

private fun formatter(options: ULong) =
    NSISO8601DateFormatter().apply { formatOptions = options }

internal actual fun parseIsoInstantToEpochMillis(raw: String): Long? = runCatching {
    // Try fractional-seconds format first (covers ".000Z" timestamps from Komga);
    // fall back to the plain internet-date-time format for timestamps without fractional seconds.
    val date = formatter(WITH_FRACTIONAL).dateFromString(raw)
        ?: formatter(NSISO8601DateFormatWithInternetDateTime).dateFromString(raw)
        ?: return null
    (date.timeIntervalSince1970 * 1000.0).toLong()
}.getOrNull()
