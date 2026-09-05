package com.riffle.core.catalog.komga

/** Parse an ISO-8601 instant string (e.g. "2023-01-15T10:30:00.000Z") to epoch millis. */
internal expect fun parseIsoInstantToEpochMillis(raw: String): Long?
