package com.riffle.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Wraps the readaloud mini player so a swipe **up** switches to the single large player (the audiobook
 * player), continuing from the current position. A small drag handle hints the gesture. Taps still
 * reach the mini-player buttons underneath — [detectVerticalDragGestures] only consumes after touch
 * slop, and a tap never moves. The handle is overlaid, so it doesn't change the bar height (the
 * paginated page reserve is sized to that height).
 */
@Composable
fun ReadaloudPeek(
    handleColor: Color,
    onSwipeUp: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onDragHint: () -> Unit = {},
    onDragAbandoned: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    Box(
        modifier = modifier.pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = {
                    total = 0f
                    onDragHint()
                },
                onVerticalDrag = { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                },
                onDragEnd = {
                    if (total < -SWIPE_UP_THRESHOLD_PX) onSwipeUp() else onDragAbandoned()
                },
            )
        },
    ) {
        content()
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(handleColor.copy(alpha = 0.4f))
                .size(width = 32.dp, height = 4.dp),
        )
    }
}

private const val SWIPE_UP_THRESHOLD_PX = 60f
