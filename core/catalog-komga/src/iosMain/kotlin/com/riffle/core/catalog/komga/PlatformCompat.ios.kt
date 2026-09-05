package com.riffle.core.catalog.komga

import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.timeIntervalSince1970

internal actual fun parseIsoInstantToEpochMillis(raw: String): Long? = runCatching {
    val date = NSISO8601DateFormatter().dateFromString(raw) ?: return null
    (date.timeIntervalSince1970 * 1000.0).toLong()
}.getOrNull()
