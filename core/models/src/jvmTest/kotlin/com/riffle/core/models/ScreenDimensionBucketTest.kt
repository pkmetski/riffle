package com.riffle.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenDimensionBucketTest {

    @Test
    fun encode_nonCompact_producesExpanded_Medium() {
        assertEquals("Expanded_Medium", ScreenDimensionBucket.NonCompact.encode())
    }

    @Test
    fun decode_roundTrip_allCombinations() {
        for (w in ScreenDimensionBucket.Width.entries) {
            for (h in ScreenDimensionBucket.Height.entries) {
                val original = ScreenDimensionBucket(w, h)
                assertEquals(original, ScreenDimensionBucket.decode(original.encode()))
            }
        }
    }

    @Test
    fun nonCompact_isExpanded_Medium() {
        assertEquals(ScreenDimensionBucket.Width.Expanded, ScreenDimensionBucket.NonCompact.widthSizeClass)
        assertEquals(ScreenDimensionBucket.Height.Medium, ScreenDimensionBucket.NonCompact.heightSizeClass)
    }
}
