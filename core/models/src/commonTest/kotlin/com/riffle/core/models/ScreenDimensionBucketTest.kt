package com.riffle.core.models

import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test

class ScreenDimensionBucketTest {

    @Test
    fun encode_phonePortrait_produces_Compact_Medium() {
        assertEquals(ScreenDimensionBucket.PhonePortrait.encode(), "Compact_Medium")
    }

    @Test
    fun decode_roundTrip_allCombinations() {
        for (a in SizeClass.entries) {
            for (b in SizeClass.entries) {
                val original = ScreenDimensionBucket.of(a, b)
                assertEquals(original, ScreenDimensionBucket.decode(original.encode()))
            }
        }
    }

    @Test
    fun phonePortrait_isCompact_Medium() {
        assertEquals(SizeClass.Compact, ScreenDimensionBucket.PhonePortrait.narrower)
        assertEquals(SizeClass.Medium, ScreenDimensionBucket.PhonePortrait.wider)
    }

    @Test
    fun of_swaps_when_first_is_larger() {
        val bucket = ScreenDimensionBucket.of(SizeClass.Expanded, SizeClass.Compact)
        assertEquals(SizeClass.Compact, bucket.narrower)
        assertEquals(SizeClass.Expanded, bucket.wider)
    }

    @Test
    fun of_portrait_and_landscape_produce_same_bucket() {
        val portrait = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Medium)
        val landscape = ScreenDimensionBucket.of(SizeClass.Medium, SizeClass.Compact)
        assertEquals(portrait, landscape)
    }

    @Test
    fun decode_throws_on_value_without_underscore() {
        assertFailsWith<IllegalArgumentException> {
            ScreenDimensionBucket.decode("CompactMedium")
        }
    }
}
