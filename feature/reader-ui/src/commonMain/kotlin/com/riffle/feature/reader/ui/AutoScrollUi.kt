package com.riffle.feature.reader.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.isHudPillVisible
import com.riffle.core.domain.autoscroll.speedOrNull
import com.riffle.feature.designsystem.TestTags

// The HUD pill anchors to BottomEnd inside the system-bar insets, but the reader still paints its
// chapter rail / reading-status overlay above the nav bar. The pill's bottom padding must clear
// that overlay strip; a small 12dp value overlaps it. Keep this >= HUD_PILL_MIN_BOTTOM_DP.
const val HUD_PILL_BOTTOM_DP: Int = 35
const val HUD_PILL_MIN_BOTTOM_DP: Int = 24

/**
 * Translucent in-content HUD pill: pause + minus + wpm + plus. Visible only while
 * [state] is [AutoScrollState.Running]. Anchored to the bottom-right inset of the screen.
 *
 * Shared by both readers. [labels] carries the host's string catalogue — see [SpeedHudLabels].
 */
@Composable
fun AutoScrollHudPill(
    state: AutoScrollState,
    labels: SpeedHudLabels,
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
                .testTag(TestTags.AUTO_SCROLL_HUD_PILL)
                .background(Color(0x66_1F_1B_17), CircleShape)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .heightIn(min = 28.dp),
        ) {
            val playPauseDescription = if (running) labels.pause else labels.resume
            IconButton(
                onClick = if (running) onPause else onResume,
                modifier = Modifier
                    .size(28.dp)
                    .testTag(TestTags.IOS_READER_AUTOSCROLL_PAUSE)
                    .semantics { contentDescription = playPauseDescription },
            ) {
                if (running) PauseGlyph(Color.White) else PlayGlyph(Color.White)
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onSlower,
                modifier = Modifier
                    .size(28.dp)
                    .testTag(TestTags.READER_AUTOSCROLL_SLOWER)
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
                    .testTag(TestTags.READER_AUTOSCROLL_FASTER)
                    .semantics { contentDescription = labels.faster },
            ) {
                PlusGlyph(Color.White)
            }
        }
    }
}
