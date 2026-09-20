package com.riffle.feature.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The four reader-HUD glyphs, drawn rather than imported.
 *
 * `Icons.Filled.{Pause,PlayArrow,Add,Remove}` come from `material-icons-core`, which Compose
 * Multiplatform 1.10 no longer publishes for Kotlin/Native — so an icon set is not something a
 * shared reader component can depend on. These are the same four shapes on a 24-unit grid, drawn
 * on a `Canvas` the way [AutoScrollToggleIcon]'s own glyph already was.
 */
@Composable
internal fun PauseGlyph(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) { drawPause(color) }
}

@Composable
internal fun PlayGlyph(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) { drawPlay(color) }
}

@Composable
internal fun PlusGlyph(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) { drawPlus(color) }
}

@Composable
internal fun MinusGlyph(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) { drawMinus(color) }
}

internal fun DrawScope.drawPause(color: Color) {
    val unit = size.width / 24f
    val barWidth = 4f * unit
    drawRect(color, topLeft = Offset(6f * unit, 5f * unit), size = Size(barWidth, 14f * unit))
    drawRect(color, topLeft = Offset(14f * unit, 5f * unit), size = Size(barWidth, 14f * unit))
}

internal fun DrawScope.drawPlay(color: Color) {
    val unit = size.width / 24f
    val path = Path().apply {
        moveTo(7f * unit, 5f * unit)
        lineTo(19f * unit, 12f * unit)
        lineTo(7f * unit, 19f * unit)
        close()
    }
    drawPath(path, color)
}

internal fun DrawScope.drawPlus(color: Color) {
    val unit = size.width / 24f
    val thickness = 2.6f * unit
    drawRect(color, topLeft = Offset(4f * unit, 12f * unit - thickness / 2f), size = Size(16f * unit, thickness))
    drawRect(color, topLeft = Offset(12f * unit - thickness / 2f, 4f * unit), size = Size(thickness, 16f * unit))
}

internal fun DrawScope.drawMinus(color: Color) {
    val unit = size.width / 24f
    val thickness = 2.6f * unit
    drawRect(color, topLeft = Offset(4f * unit, 12f * unit - thickness / 2f), size = Size(16f * unit, thickness))
}
