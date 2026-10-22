package com.riffle.feature.reader

import com.riffle.feature.reader.ScrollProbes.BoundaryAndProgression
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parser correctness for [ScrollProbes.parseBoundaryAndProgression].
 *
 * Runs on both JVM (Android CI `test jvmTest`) and `iosSimulatorArm64Test` — the parser is pure
 * and shared so a bug in the parsing logic would affect both platforms equally.
 */
class ScrollProbesTest {

    @Test
    fun parseBoundaryAndProgressionExtractsBoundaryAndProgress() {
        assertEquals(
            BoundaryAndProgression(
                boundary = NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false),
                scrollProgression = 0.8765f,
            ),
            ScrollProbes.parseBoundaryAndProgression("true,false,0.8765"),
        )
    }

    @Test
    fun parseBoundaryAndProgressionHandlesAtBackwardBoundary() {
        assertEquals(
            BoundaryAndProgression(
                boundary = NavigatorScrollBoundary(atForwardBoundary = false, atBackwardBoundary = true),
                scrollProgression = 0f,
            ),
            ScrollProbes.parseBoundaryAndProgression("false,true,0.0000"),
        )
    }

    @Test
    fun parseBoundaryAndProgressionClampsProgressionAboveOne() {
        val result = ScrollProbes.parseBoundaryAndProgression("false,false,1.5000")
        assertEquals(1f, result.scrollProgression)
    }

    @Test
    fun parseBoundaryAndProgressionClampsProgressionBelowZero() {
        val result = ScrollProbes.parseBoundaryAndProgression("false,false,-0.1000")
        assertEquals(0f, result.scrollProgression)
    }

    @Test
    fun parseBoundaryAndProgressionFallsBackOnNullInput() {
        val result = ScrollProbes.parseBoundaryAndProgression(null)
        assertEquals(NavigatorScrollBoundary.None, result.boundary)
        assertEquals(0f, result.scrollProgression)
    }

    @Test
    fun parseBoundaryAndProgressionFallsBackOnMissingProgressionField() {
        val result = ScrollProbes.parseBoundaryAndProgression("true,false")
        assertEquals(NavigatorScrollBoundary.None, result.boundary)
        assertEquals(0f, result.scrollProgression)
    }

    @Test
    fun parseBoundaryAndProgressionStripsQuoteLayer() {
        val result = ScrollProbes.parseBoundaryAndProgression("\"true,false,0.5000\"")
        assertEquals(NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false), result.boundary)
        assertEquals(0.5f, result.scrollProgression)
    }
}
