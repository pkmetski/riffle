package com.riffle.core.common

import java.time.Instant

actual fun formatIso8601(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

actual fun parseIso8601ToEpochMillis(iso: String): Long? =
    runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
