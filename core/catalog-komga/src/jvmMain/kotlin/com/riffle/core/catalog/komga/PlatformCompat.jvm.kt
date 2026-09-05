package com.riffle.core.catalog.komga

internal actual fun parseIsoInstantToEpochMillis(raw: String): Long? =
    runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
