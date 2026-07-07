package com.riffle.app.feature.reader.cadence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.speedOrNull

/**
 * The reader top-bar toggle for Cadence. When idle, draws the agreed single-colour glyph from issue
 * #403's prototype: three horizontal text bars with a taller, solid middle bar (the "current
 * sentence" cue) and a right-pointing play triangle. When running, swaps to a filled Pause glyph so
 * the tap-to-stop affordance matches Auto-Scroll's convention.
 *
 * The middle bar's height contrast — not a highlight colour — is what carries the "current
 * sentence" cue. Everything is a single [LocalContentColor] fill, matching every other reader
 * top-bar icon.
 */
@Composable
fun CadenceToggleIcon(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = if (isRunning) "Stop cadence" else "Start cadence"
        },
    ) {
        if (isRunning) {
            Icon(Icons.Filled.Pause, contentDescription = null)
        } else {
            val color = LocalContentColor.current
            Canvas(modifier = Modifier.size(24.dp)) {
                drawCadenceGlyph(color)
            }
        }
    }
}

/**
 * Hero-size rendition of the same glyph, for the Settings drill-in About blurb. Same shape as
 * [CadenceToggleIcon]; larger. Caller places it above the About text.
 */
@Composable
fun CadenceHeroIcon(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier.size(size)) {
        drawCadenceGlyph(color)
    }
}

// The glyph — three horizontal text bars with a taller, solid middle bar plus a right-facing play
// triangle. Everything is single-colour; the "current sentence" cue is carried by the middle bar's
// height/width contrast alone. Reference values match issue #403's agreed SVG:
//   rect (3, 5,   12, 1.6, r=.8)   ← outer text bar
//   rect (3, 10,  11, 4,   r=1)    ← middle (current sentence)
//   path M15.5 8 L21 12 L15.5 16 Z ← play triangle
//   rect (3, 17.4, 12, 1.6, r=.8)  ← outer text bar
internal fun DrawScope.drawCadenceGlyph(color: Color) {
    val unit = size.width / 24f
    // Top outer bar
    drawRect(color, topLeft = Offset(3f * unit, 5f * unit), size = Size(12f * unit, 1.6f * unit))
    // Middle "current sentence" — taller AND same-color, standing out by height/width contrast alone
    drawRect(color, topLeft = Offset(3f * unit, 10f * unit), size = Size(11f * unit, 4f * unit))
    // Right-pointing play triangle
    val play = Path().apply {
        moveTo(15.5f * unit, 8f * unit)
        lineTo(21f * unit, 12f * unit)
        lineTo(15.5f * unit, 16f * unit)
        close()
    }
    drawPath(play, color)
    // Bottom outer bar
    drawRect(color, topLeft = Offset(3f * unit, 17.4f * unit), size = Size(12f * unit, 1.6f * unit))
}

// The HUD pill anchors to BottomEnd inside the system-bar insets. Match Auto-Scroll's
// [com.riffle.app.feature.reader.autoscroll.HUD_PILL_BOTTOM_DP] so the two pills sit at the same
// baseline when both features could hypothetically be visible (they can't — mutual exclusion — but
// the visual language stays consistent when the user toggles between them).
private const val HUD_PILL_BOTTOM_DP: Int = 35

/**
 * Translucent in-content HUD pill for Cadence: pause + minus + wpm + plus (issue #403).
 * Visible only while [state] is [CadenceState.Running] or [CadenceState.Paused]; anchored to the
 * bottom-right inset of the screen. Mirrors [com.riffle.app.feature.reader.autoscroll.AutoScrollHudPill]
 * so the two features feel identical when the user swaps between them.
 */
@Composable
fun CadenceHudPill(
    state: CadenceState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is CadenceState.Idle) return
    val speed = state.speedOrNull?.wpm ?: return
    val running = state is CadenceState.Running

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
            IconButton(
                onClick = if (running) onPause else onResume,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (running) "Pause cadence" else "Resume cadence",
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
