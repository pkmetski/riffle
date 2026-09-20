package com.riffle.feature.reader.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.speedOrNull

/**
 * Cadence's two reader surfaces — the top-bar toggle and the HUD pill — rendered by both hosts.
 *
 * They used to live in `:app`, which made them Android-only by construction; iOS would have had
 * to draw its own and the two would have drifted the way every other duplicated reader surface
 * in this repo has. Same module, same reason, same shape as [AutoScrollToggleIcon] /
 * [AutoScrollHudPill], with which Cadence deliberately shares its visual language: the two
 * features are mutually exclusive, so only one pill is ever on screen, and swapping between them
 * should not move anything.
 */
@Composable
fun CadenceToggleIcon(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .testTag("cadence_toggle")
            .semantics {
                contentDescription = if (isRunning) "Stop cadence" else "Start cadence"
            },
    ) {
        if (isRunning) {
            PauseGlyph(LocalContentColor.current, size = 24.dp)
        } else {
            CadenceGlyph(LocalContentColor.current, size = 24.dp)
        }
    }
}

/**
 * The Cadence glyph at an arbitrary [size]. The reader top-bar toggle draws it at 24.dp; the
 * Settings drill-in's hero rendition is the same shape, larger.
 */
@Composable
fun CadenceGlyph(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) { drawCadenceGlyph(color) }
}

// Three horizontal text bars with a taller, solid middle bar plus a right-facing play triangle.
// Everything is single-colour; the "current sentence" cue is carried by the middle bar's
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

/**
 * Translucent in-content HUD pill for Cadence: pause + minus + wpm + plus (issue #403).
 *
 * Visible only while [state] is [CadenceState.Running] or [CadenceState.Paused]; anchored to the
 * bottom-right inset at [HUD_PILL_BOTTOM_DP], the same baseline [AutoScrollHudPill] uses.
 * [labels] carries the host's string catalogue — `:app` fills it from `res/values*`, `:shared`
 * from [SpeedHudLabels.EnglishCadence].
 */
@Composable
fun CadenceHudPill(
    state: CadenceState,
    labels: SpeedHudLabels,
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
                .testTag("cadence_hud_pill")
                .background(Color(0x66_1F_1B_17), CircleShape)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .heightIn(min = 28.dp),
        ) {
            val playPauseDescription = if (running) labels.pause else labels.resume
            IconButton(
                onClick = if (running) onPause else onResume,
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = playPauseDescription },
            ) {
                if (running) PauseGlyph(Color.White) else PlayGlyph(Color.White)
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onSlower,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("cadence_slower")
                    .semantics { contentDescription = labels.slower },
            ) {
                MinusGlyph(Color.White)
            }
            Text(
                text = formatTemplate(labels.wordsPerMinute, speed),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(
                onClick = onFaster,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("cadence_faster")
                    .semantics { contentDescription = labels.faster },
            ) {
                PlusGlyph(Color.White)
            }
        }
    }
}
