package com.riffle.app.feature.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffle.app.feature.audio.PlayerSurface
import com.riffle.app.feature.audio.PlayerSurfaceActions
import com.riffle.app.feature.audio.PlayerSurfaceState

/**
 * Full-screen [Audiobook Player] (ADR 0029): square cover, title/author, current-chapter label, a
 * seekable chapter-map scrubber (vertical playhead + chapter ticks + current-chapter band) with dual
 * chapter/book time, and a centered transport cluster — rewind 15s · prev chapter · play/pause · next
 * chapter · forward 30s — with the speed control in a separate utility row. Chapter controls disable
 * when the book has no chapter markers.
 */
// Minimum downward drag (px) on the player to trigger the switch to the readaloud reader — a
// deliberate swipe, not an accidental nudge.
private const val SWITCH_TO_READALOUD_THRESHOLD_PX = 160f

/** Caption hinting that dragging the cover down switches to the read-along reader. */
@Composable
private fun ReadAlongSwipeHint() {
    Text(
        "Swipe the cover down to read along",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
fun AudiobookPlayerScreen(
    onNavigateBack: () -> Unit,
    onSwitchToReadaloud: (ebookItemId: String, atSec: Double) -> Unit = { _, _ -> },
    viewModel: AudiobookPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Read fresh inside the gesture (it's keyed on Unit, so it must not capture a stale position).
    val latestState = rememberUpdatedState(state)

    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
        ),
    )
    // Swipe DOWN on the cover → switch to the readaloud reader (only when this title has a linked
    // readaloud ebook). Confined to the cover so it never sits under the collapse chevron or the
    // transport controls — "drag the artwork down" idiom. Down = toward reading.
    val coverSwipeModifier = Modifier.pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onVerticalDrag = { change, dragAmount -> total += dragAmount; change.consume() },
            onDragEnd = {
                val s = latestState.value
                val ebookId = s.readaloudEbookItemId
                if (total > SWITCH_TO_READALOUD_THRESHOLD_PX && ebookId != null) {
                    // Release the shared player to readaloud (without stopping it) before navigating,
                    // so readaloud keeps playing through the handoff.
                    viewModel.prepareReadaloudHandoff()
                    onSwitchToReadaloud(ebookId, s.positionSec)
                }
            },
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(horizontal = 24.dp),
        ) {
            // Swipe-down → read-along affordance, only when this title has a linked readaloud ebook.
            if (state.readaloudEbookItemId != null) {
                ReadAlongSwipeHint()
            }
            // Collapse affordance.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
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

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("This audiobook can't be played right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> PlayerSurface(
                    state = PlayerSurfaceState(
                        title = state.title,
                        author = state.author,
                        coverUrl = state.coverUrl,
                        authToken = state.authToken,
                        isPlaying = state.isPlaying,
                        speed = state.speed,
                        positionSec = state.positionSec,
                        durationSec = state.durationSec,
                        currentChapterTitle = state.currentChapterTitle,
                        chapterStartsSec = state.chapterStartsSec,
                        canPreviousChapter = state.canPreviousChapter,
                        canNextChapter = state.canNextChapter,
                    ),
                    actions = PlayerSurfaceActions(
                        onSeek = viewModel::seekTo,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onRewind = viewModel::rewind,
                        onForward = viewModel::forward,
                        onPreviousChapter = viewModel::previousChapter,
                        onNextChapter = viewModel::nextChapter,
                        onSpeedChange = viewModel::setSpeed,
                    ),
                    coverModifier = if (state.readaloudEbookItemId != null) coverSwipeModifier else Modifier,
                )
            }
        }
    }
}
