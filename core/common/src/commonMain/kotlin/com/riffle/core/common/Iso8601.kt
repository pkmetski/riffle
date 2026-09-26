package com.riffle.core.common

/**
 * Formats [epochMillis] as an ISO-8601 UTC instant, e.g. `2021-12-20T10:30:45.123Z`.
 *
 * The output is the wire format of every annotation-sync timestamp (`created` / `modified` in the
 * W3C body, `lastSyncedAt` in the device sentinel), so both platforms must produce a string the
 * other can parse with [parseIso8601ToEpochMillis]. Fractional seconds may be omitted when they are
 * zero (the JVM does that); parsers on both platforms accept either shape.
 */
expect fun formatIso8601(epochMillis: Long): String

/** Parses an ISO-8601 instant (with or without fractional seconds) to epoch millis; null if malformed. */
expect fun parseIso8601ToEpochMillis(iso: String): Long?
