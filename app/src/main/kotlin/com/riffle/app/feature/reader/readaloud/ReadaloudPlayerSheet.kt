package com.riffle.app.feature.reader.readaloud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.audio.PlayerSurface
import com.riffle.app.feature.audio.PlayerSurfaceActions
import com.riffle.app.feature.audio.PlayerSurfaceState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Shared drag/collapse handle for the readaloud player. [progress] runs 0f (peek = mini player) → 1f
 * (expanded = full-screen player). [ReadaloudPeek] and [ReadaloudExpandedOverlay] both read it, and
 * the reader collapses the sheet on a back press through it.
 *
 * The mini player is NOT torn out of its place in the reader's bottom stack — swiping it up slides a
 * separate full-screen overlay up *over* it ([ReadaloudExpandedOverlay]); collapsing slides that back
 * down to reveal the untouched mini player. This keeps the documented-fragile chapter-rail / page
 * reserve layout exactly as it was when collapsed. Nothing about playback changes — it is one session
 * shown two ways.
 */
class ReadaloudSheetState {
    /** 0f peek, 1f expanded. Drives the overlay offset, background, scrim, and the peek's drag math. */
    internal val progress = Animatable(0f)

    /** The expanded overlay's measured full height (px) — the drag distance peek↔expanded. */
    internal var rangePx: Float = DEFAULT_RANGE_PX

    val isExpanded: Boolean get() = progress.value > 0.5f

    suspend fun expand() = progress.animateTo(1f, tween(ANIM_MS))
    suspend fun collapse() = progress.animateTo(0f, tween(ANIM_MS))

    internal suspend fun snapToNearest() = if (progress.value >= 0.5f) expand() else collapse()
    internal suspend fun nudge(deltaProgress: Float) =
        progress.snapTo((progress.value + deltaProgress).coerceIn(0f, 1f))

    private companion object {
        const val ANIM_MS = 280
        const val DEFAULT_RANGE_PX = 1600f
    }
}

@Composable
fun rememberReadaloudSheetState(): ReadaloudSheetState = remember { ReadaloudSheetState() }

/**
 * Wraps the mini player so a swipe **up** expands the player. Taps still reach the mini-player buttons
 * underneath: [detectVerticalDragGestures] only consumes after touch slop, and a tap never moves.
 */
@Composable
fun ReadaloudPeek(
    sheetState: ReadaloudSheetState,
    handleColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch { sheetState.nudge(-dragAmount / sheetState.rangePx) }
                },
                onDragEnd = { scope.launch { sheetState.snapToNearest() } },
                onDragCancel = { scope.launch { sheetState.snapToNearest() } },
            )
        },
    ) {
        content()
        // Swipe-up affordance: a small drag handle pinned to the top edge of the bar. Overlaid, so it
        // doesn't change the mini-player height (the paginated page reserve is sized to that height).
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

/**
 * The full-screen expanded player ([PlayerSurface] — the same surface the standalone Audiobook player
 * uses). Sits below the screen when collapsed and slides up as [ReadaloudSheetState.progress] grows;
 * fully covers the reader at 1f. Drag down (or the collapse chevron) shrinks it back to the mini
 * player. Render it as a full-screen sibling overlay in the reader.
 */
@Composable
fun ReadaloudExpandedOverlay(
    playerState: PlayerSurfaceState,
    actions: PlayerSurfaceActions,
    sheetState: ReadaloudSheetState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fullPx = constraints.maxHeight.toFloat()
        SideEffect { sheetState.rangePx = fullPx }

        val progress = sheetState.progress.value
        if (progress <= 0.001f) return@BoxWithConstraints // fully collapsed: transparent, no input

        // Scrim over the reader, fading in with expansion (consumes no pointer events).
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = progress * 0.7f)))

        val offsetY = ((1f - progress) * fullPx).roundToInt()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY) }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(fullPx) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { sheetState.nudge(-dragAmount / fullPx) }
                        },
                        onDragEnd = { scope.launch { sheetState.snapToNearest() } },
                        onDragCancel = { scope.launch { sheetState.snapToNearest() } },
                    )
                }
                .testTag("readaloud_player_sheet"),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                ExpandedHeader(onCollapse = { scope.launch { sheetState.collapse() } })
                PlayerSurface(state = playerState, actions = actions)
            }
        }
    }
}

/** Collapse chevron + label at the top of the expanded surface (mirrors the audiobook collapse row). */
@Composable
private fun ExpandedHeader(onCollapse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.testTag("readaloud_collapse")) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
        }
        Text(
            "NOW PLAYING",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(48.dp))
    }
}
