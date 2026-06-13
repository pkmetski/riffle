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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
 * Drag progress / collapse handle for [ReadaloudPlayerSheet], hoisted so the reader can collapse the
 * sheet on a back press. [progress] runs 0f (peek = mini player) → 1f (expanded = full-screen player).
 */
class ReadaloudSheetState {
    /** 0f peek, 1f expanded. Drives the sheet offset, cross-fade, scrim, and corner radius. */
    internal val progress = Animatable(0f)

    val isExpanded: Boolean get() = progress.value > 0.5f

    suspend fun expand() = progress.animateTo(1f, tween(ANIM_MS))
    suspend fun collapse() = progress.animateTo(0f, tween(ANIM_MS))

    internal suspend fun snapToNearest() = if (progress.value >= 0.5f) expand() else collapse()
    internal suspend fun nudge(deltaProgress: Float) =
        progress.snapTo((progress.value + deltaProgress).coerceIn(0f, 1f))

    private companion object {
        const val ANIM_MS = 280
    }
}

@Composable
fun rememberReadaloudSheetState(): ReadaloudSheetState = remember { ReadaloudSheetState() }

/**
 * A bottom sheet whose **peek** state is the Readaloud mini player and whose **expanded** state is the
 * full-screen [PlayerSurface] — the same surface the standalone Audiobook player uses. Swiping the
 * mini player up grows it into the full player; dragging down (or the collapse chevron) shrinks it
 * back. The reader stays mounted behind the sheet, so collapsing returns to the exact reading spot —
 * nothing about playback changes, it is one session shown two ways.
 *
 * The drag is a plain vertical-drag → [Animatable] progress with midpoint snap (no [androidx.compose
 * .foundation.gestures.AnchoredDraggableState], whose constructor shifts across Compose versions).
 * Taps land on the mini-player buttons because the vertical-drag detector only consumes after touch
 * slop; a tap never moves, so it is never captured here.
 */
@Composable
fun ReadaloudPlayerSheet(
    playerState: PlayerSurfaceState,
    actions: PlayerSurfaceActions,
    sheetState: ReadaloudSheetState,
    peekContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var peekHeightPx by remember { mutableFloatStateOf(DEFAULT_PEEK_PX) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fullPx = constraints.maxHeight.toFloat()
        val range = (fullPx - peekHeightPx).coerceAtLeast(1f)
        val progress = sheetState.progress.value
        val offsetY = ((1f - progress) * range).roundToInt()
        val cornerDp = (26f * (1f - progress)).dp

        // Scrim over the reader behind the sheet, fading in with expansion. A plain background Box
        // consumes no pointer events, so the reader stays interactive while the sheet is collapsed.
        if (progress > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = progress * 0.7f)))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY) }
                .clip(RoundedCornerShape(topStart = cornerDp, topEnd = cornerDp))
                // Transparent at peek so the mini player paints its own theme-following bar; opaque
                // once expanded so the reader can't show through the full player.
                .background(MaterialTheme.colorScheme.background.copy(alpha = progress))
                .pointerInput(range) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { sheetState.nudge(-dragAmount / range) }
                        },
                        onDragEnd = { scope.launch { sheetState.snapToNearest() } },
                        onDragCancel = { scope.launch { sheetState.snapToNearest() } },
                    )
                }
                .testTag("readaloud_player_sheet"),
        ) {
            // Peek (mini player) pinned to the top strip; fades out as the sheet expands.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { if (it.height > 0) peekHeightPx = it.height.toFloat() }
                    .alpha((1f - progress * 1.8f).coerceIn(0f, 1f)),
            ) {
                peekContent()
            }

            // Expanded full-screen surface; fades in. Skipped entirely while fully collapsed so the
            // mini player keeps all input.
            if (progress > 0.02f) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .alpha(((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)),
                ) {
                    ExpandedHeader(onCollapse = { scope.launch { sheetState.collapse() } })
                    PlayerSurface(state = playerState, actions = actions)
                }
            }
        }
    }
}

/** Collapse chevron + "pull down" hint at the top of the expanded surface (mirrors the audiobook
 *  player's collapse row). */
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

// A sane starting peek height (≈ the mini-player bar) used only until the real height is measured.
private const val DEFAULT_PEEK_PX = 160f
