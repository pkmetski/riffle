package com.riffle.core.catalog.komga

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression: NSISO8601DateFormatter without NSISO8601DateFormatWithFractionalSeconds silently
 * returned nil for timestamps containing ".000Z", making Komga series/book modified dates
 * disappear on iOS (#1208). Both forms must parse to a non-null epoch millis value.
 */
class IosParseIsoInstantTest {

    @Test
    fun fractionalSecondsTimestampParsesSuccessfully() {
        // Komga emits milliseconds in its modified dates; this form was broken before the fix.
        val result = parseIsoInstantToEpochMillis("2023-01-15T10:30:00.000Z")
        assertNotNull(result, "Timestamp with fractional seconds must parse to non-null")
    }

    @Test
    fun plainTimestampParsesSuccessfully() {
        val result = parseIsoInstantToEpochMillis("2023-01-15T10:30:00Z")
        assertNotNull(result, "Timestamp without fractional seconds must also parse")
    }

    @Test
    fun fractionalSecondsTimestampYieldsCorrectEpochMillis() {
        val result = parseIsoInstantToEpochMillis("2023-01-15T10:30:00.000Z")
        // 2023-01-15T10:30:00Z = 1673778600 seconds = 1673778600000 ms
        assertNotNull(result)
        assertTrue(result > 1_673_000_000_000L, "Epoch millis $result looks wrong for 2023-01-15")
    }

    @Test
    fun malformedTimestampReturnsNull() {
        assertNull(parseIsoInstantToEpochMillis("not-a-date"))
    }
}
