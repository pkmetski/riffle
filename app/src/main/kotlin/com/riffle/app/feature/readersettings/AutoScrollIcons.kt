package com.riffle.app.feature.readersettings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The top-bar toggle icon for Auto-Scroll. When idle, draws the "Play↓ under even text" glyph
 * (three centred text bars over a downward-pointing play triangle). When running, draws a pause
 * glyph so the tap-to-stop affordance is obvious.
 */
@Composable
fun AutoScrollToggleIcon(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = if (isRunning) "Stop auto-scroll" else "Start auto-scroll"
        },
    ) {
        if (isRunning) {
            Icon(Icons.Filled.Pause, contentDescription = null)
        } else {
            // Three centred text bars + downward play apex — drawn as a single Canvas so the
            // proportions stay tight inside the standard 24×24 icon box.
            val iconColor = LocalContentColor.current
            Canvas(modifier = Modifier.size(24.dp)) {
                drawPlayDownUnderEvenText(iconColor)
            }
        }
    }
}

private fun DrawScope.drawPlayDownUnderEvenText(color: Color) {
    // Coordinates are in the canvas's pixel space; the 24×24 dp canvas converts to ~ size.width.
    val w = size.width
    val unit = w / 24f
    // Three text bars centred at the triangle's top width (10 units wide → x=7..17 in 24-grid).
    val textColor = color.copy(alpha = 0.55f)
    val barHeight = 1.4f * unit
    val barX = 7f * unit
    val barW = 10f * unit
    drawRect(textColor, topLeft = Offset(barX, 3f * unit), size = Size(barW, barHeight))
    drawRect(textColor, topLeft = Offset(barX, 6f * unit), size = Size(barW, barHeight))
    drawRect(textColor, topLeft = Offset(barX, 9f * unit), size = Size(barW, barHeight))
    // Solid play triangle apex
    val path = Path().apply {
        moveTo(7f * unit, 12f * unit)
        lineTo(17f * unit, 12f * unit)
        lineTo(12f * unit, 20f * unit)
        close()
    }
    drawPath(path, color)
}
