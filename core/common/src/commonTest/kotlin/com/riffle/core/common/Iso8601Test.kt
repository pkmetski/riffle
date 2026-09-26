package com.riffle.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the ISO-8601 seam both platforms use for annotation-sync timestamps. Runs on the JVM and
 * on the iOS simulator, so a divergence between `java.time.Instant` and `NSISO8601DateFormatter`
 * (fractional seconds, the trailing `Z`, offset handling) fails here rather than as a silently
 * mis-ordered LWW merge between an Android and an iOS device.
 */
class Iso8601Test {

    // 2021-12-20T10:30:45.123Z
    private val millis = 1_639_996_245_123L

    @Test
    fun formatsWithFractionalSecondsWhenNonZero() {
        assertEquals("2021-12-20T10:30:45.123Z", formatIso8601(millis))
    }

    @Test
    fun formatsWithoutFractionWhenMillisAreZero() {
        assertEquals("2021-12-20T10:30:45Z", formatIso8601(1_639_996_245_000L))
    }

    @Test
    fun parsesFractionalAndWholeSecondShapes() {
        assertEquals(millis, parseIso8601ToEpochMillis("2021-12-20T10:30:45.123Z"))
        assertEquals(1_639_996_245_000L, parseIso8601ToEpochMillis("2021-12-20T10:30:45Z"))
    }

    @Test
    fun roundTripsThroughFormatAndParse() {
        assertEquals(millis, parseIso8601ToEpochMillis(formatIso8601(millis)))
        assertEquals(0L, parseIso8601ToEpochMillis(formatIso8601(0L)))
    }

    @Test
    fun malformedInputParsesToNull() {
        assertNull(parseIso8601ToEpochMillis("not a date"))
        assertNull(parseIso8601ToEpochMillis(""))
    }
}
