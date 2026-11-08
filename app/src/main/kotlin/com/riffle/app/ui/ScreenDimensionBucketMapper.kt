package com.riffle.app.ui

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass

fun WindowSizeClass.toScreenDimensionBucket(): ScreenDimensionBucket =
    ScreenDimensionBucket.of(
        a = widthSizeClass.toSizeClass(),
        b = heightSizeClass.toSizeClass(),
    )

private fun WindowWidthSizeClass.toSizeClass(): SizeClass = when (this) {
    WindowWidthSizeClass.Compact -> SizeClass.Compact
    WindowWidthSizeClass.Medium -> SizeClass.Medium
    else -> SizeClass.Expanded
}

private fun WindowHeightSizeClass.toSizeClass(): SizeClass = when (this) {
    WindowHeightSizeClass.Compact -> SizeClass.Compact
    WindowHeightSizeClass.Medium -> SizeClass.Medium
    else -> SizeClass.Expanded
}
