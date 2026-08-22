package com.riffle.app.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class ScreenDimensionBucketMapperTest {

    private fun sizeClass(width: WindowWidthSizeClass, height: WindowHeightSizeClass): WindowSizeClass =
        WindowSizeClass.calculateFromSize(
            DpSize(
                width = when (width) {
                    WindowWidthSizeClass.Compact -> Dp(400f)
                    WindowWidthSizeClass.Medium -> Dp(700f)
                    else -> Dp(900f)
                },
                height = when (height) {
                    WindowHeightSizeClass.Compact -> Dp(400f)
                    WindowHeightSizeClass.Medium -> Dp(700f)
                    else -> Dp(900f)
                },
            ),
        )

    @Test
    fun phone_portrait_maps_to_Compact_Medium() {
        val result = sizeClass(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium).toScreenDimensionBucket()
        assertEquals(ScreenDimensionBucket(SizeClass.Compact, SizeClass.Medium), result)
    }

    @Test
    fun tablet_portrait_maps_to_Expanded_Expanded() {
        val result = sizeClass(WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Expanded).toScreenDimensionBucket()
        assertEquals(ScreenDimensionBucket(SizeClass.Expanded, SizeClass.Expanded), result)
    }

    @Test
    fun portrait_and_landscape_produce_same_bucket() {
        val portrait = sizeClass(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium).toScreenDimensionBucket()
        val landscape = sizeClass(WindowWidthSizeClass.Medium, WindowHeightSizeClass.Compact).toScreenDimensionBucket()
        assertEquals(portrait, landscape)
    }

    @Test
    fun narrower_is_always_lte_wider() {
        for (w in listOf(WindowWidthSizeClass.Compact, WindowWidthSizeClass.Medium, WindowWidthSizeClass.Expanded)) {
            for (h in listOf(WindowHeightSizeClass.Compact, WindowHeightSizeClass.Medium, WindowHeightSizeClass.Expanded)) {
                val bucket = sizeClass(w, h).toScreenDimensionBucket()
                assert(bucket.narrower.ordinal <= bucket.wider.ordinal) {
                    "Expected narrower <= wider but got ${bucket.narrower} > ${bucket.wider}"
                }
            }
        }
    }
}
