package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [ColumnSnap.parseNarratedColumnsResult] — the Kotlin half of the contract
 * between the in-WebView JS ([ColumnSnap.measureNarratedColumnsJs]) and its Kotlin caller
 * ([ColumnSnap.measureNarratedColumns]). The on-device JS behaviour is exercised in
 * `NarratedColumnsJsTest`; this file pins down how the Kotlin side translates each protocol
 * variant the JS can emit (and every malformed thing it shouldn't) into a stable List<Double>.
 *
 * Trivia: `evaluateJavascript` wraps a JS string return in JSON quotes — `return "off"` arrives
 * Kotlin-side as the literal `"\"off\""`. The parser trims those, which is why every input here
 * carries the wrapping quotes.
 */
class NarratedColumnsResultParserTest {

    @Test
    fun nullEvaluateResult_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult(null))
    }

    @Test
    fun offSentinel_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"off\""))
    }

    @Test
    fun scrollSentinel_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"scroll\""))
    }

    @Test
    fun singleColumnFraction() {
        assertEquals(listOf(1.0), ColumnSnap.parseNarratedColumnsResult("\"[1.0]\""))
    }

    @Test
    fun twoColumnFractions() {
        assertEquals(listOf(0.6, 1.0), ColumnSnap.parseNarratedColumnsResult("\"[0.6, 1.0]\""))
    }

    @Test
    fun threeColumnFractions_preservesOrder() {
        assertEquals(
            listOf(0.25, 0.6, 1.0),
            ColumnSnap.parseNarratedColumnsResult("\"[0.25, 0.6, 1.0]\""),
        )
    }

    @Test
    fun emptyJsonArray_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"[]\""))
    }

    @Test
    fun integerValuesAreCoercedToDoubles() {
        // JSON allows integers in a number array; the cumulative fractions are doubles
        // semantically but the JSON serialiser may drop trailing zeros.
        assertEquals(listOf(1.0), ColumnSnap.parseNarratedColumnsResult("\"[1]\""))
    }

    @Test
    fun malformedJson_returnsEmpty() {
        // Defensive: a parse failure on a runaway JS error must not crash the playback loop.
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"[not, valid\""))
    }

    @Test
    fun unexpectedSentinel_returnsEmpty() {
        // Any future JS sentinel that isn't "off"/"scroll" and doesn't start with "[" is treated
        // the same as off: empty list, no exception.
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"something_else\""))
    }

    @Test
    fun whitespaceAroundResultIsTrimmed() {
        assertEquals(listOf(0.5, 1.0), ColumnSnap.parseNarratedColumnsResult("\"  [0.5, 1.0]  \""))
    }
}
