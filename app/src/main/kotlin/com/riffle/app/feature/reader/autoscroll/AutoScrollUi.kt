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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.isHudPillVisible
import com.riffle.core.domain.autoscroll.speedOrNull

// The HUD pill anchors to BottomEnd inside the system-bar insets, but the reader still paints its
// chapter rail / reading-status overlay above the nav bar. The pill's bottom padding must clear
// that overlay strip; a small 12dp value overlaps it. Keep this >= HUD_PILL_MIN_BOTTOM_DP.
internal const val HUD_PILL_BOTTOM_DP: Int = 35
internal const val HUD_PILL_MIN_BOTTOM_DP: Int = 24

/**
 * Translucent in-content HUD pill: pause + minus + wpm + plus. Visible only while
 * [state] is [AutoScrollState.Running]. Anchored to the bottom-right inset of the screen.
 */
@Composable
fun AutoScrollHudPill(
    state: AutoScrollState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isHudPillVisible) return
    val speed = state.speedOrNull?.wpm ?: return
    val running = state is AutoScrollState.Running

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
                    contentDescription = if (running) "Pause auto-scroll" else "Resume auto-scroll",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onSlower, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_slower),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_words_per_minute, speed),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = onFaster, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_faster),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
