package com.riffle.app.feature.reader.autoscroll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.speedOrNull

// The HUD pill anchors to BottomEnd inside the system-bar insets, but the reader still paints its
// chapter rail / reading-status overlay above the nav bar. The pill's bottom padding must clear
// that overlay strip; a small 12dp value overlaps it. Keep this >= HUD_PILL_MIN_BOTTOM_DP.
internal const val HUD_PILL_BOTTOM_DP: Int = 35
internal const val HUD_PILL_MIN_BOTTOM_DP: Int = 24

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
            val iconColor = androidx.compose.material3.LocalContentColor.current
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
    drawRect(textColor, topLeft = androidx.compose.ui.geometry.Offset(barX, 3f * unit), size = androidx.compose.ui.geometry.Size(barW, barHeight))
    drawRect(textColor, topLeft = androidx.compose.ui.geometry.Offset(barX, 6f * unit), size = androidx.compose.ui.geometry.Size(barW, barHeight))
    drawRect(textColor, topLeft = androidx.compose.ui.geometry.Offset(barX, 9f * unit), size = androidx.compose.ui.geometry.Size(barW, barHeight))
    // Solid play triangle apex
    val path = Path().apply {
        moveTo(7f * unit, 12f * unit)
        lineTo(17f * unit, 12f * unit)
        lineTo(12f * unit, 20f * unit)
        close()
    }
    drawPath(path, color)
}

/**
 * Translucent in-content HUD pill: pause + minus + wpm + plus. Visible only while
 * [state] is [AutoScrollState.Running]. Anchored to the bottom-right inset of the screen.
 */
@Composable
fun AutoScrollHudPill(
    state: AutoScrollState,
    onPause: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state is AutoScrollState.Running
    if (!running) return
    val speed = state.speedOrNull?.wpm ?: return

    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(insets),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = HUD_PILL_BOTTOM_DP.dp)
                .background(Color(0x66_1F_1B_17), CircleShape)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .heightIn(min = 28.dp),
        ) {
            IconButton(onClick = onPause, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = "Pause auto-scroll",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onSlower, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = "Slower",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = "$speed wpm",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = onFaster, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Faster",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
