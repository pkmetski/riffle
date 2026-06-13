package com.riffle.app.feature.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riffle.app.R

/**
 * Everything [PlayerSurface] renders. Both the standalone Audiobook player and the in-reader
 * Readaloud expanded player map their own state into this. [positionSec], [durationSec] and
 * [chapterStartsSec] are all on ONE concatenated timeline (seconds).
 */
data class PlayerSurfaceState(
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val authToken: String = "",
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val currentChapterTitle: String? = null,
    val chapterStartsSec: List<Double> = emptyList(),
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
)

/** Callbacks the surface invokes. [onSeek] takes an absolute global position in seconds. */
data class PlayerSurfaceActions(
    val onSeek: (Double) -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onRewind: () -> Unit,
    val onForward: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSpeedChange: (Float) -> Unit,
)

/**
 * The full-screen player body (ADR 0029): square cover, title/author, current-chapter label, a
 * seekable chapter-map scrubber (vertical playhead + chapter ticks) with dual chapter/book time, and
 * a centered transport cluster — rewind 15s · prev chapter · play/pause · next chapter · forward 30s
 * — with the shared speed control in a separate utility row. Stateless: the standalone Audiobook
 * player and the in-reader Readaloud expanded player both render it from their own state.
 */
@Composable
fun PlayerSurface(
    state: PlayerSurfaceState,
    actions: PlayerSurfaceActions,
    modifier: Modifier = Modifier,
    // Applied to the cover art only. The audiobook player attaches its "drag the cover down to switch
    // to readaloud" gesture here so it never sits under the collapse chevron or transport controls.
    coverModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(state.coverUrl)
                .addHeader("Authorization", "Bearer ${state.authToken}")
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f) // audiobook covers are square (ADR 0029)
                .shadow(16.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .then(coverModifier),
        )
        Spacer(Modifier.height(20.dp))
        Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(state.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.currentChapterTitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(state.currentChapterTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(18.dp))
        ChapterSeekBar(
            positionSec = state.positionSec,
            durationSec = state.durationSec,
            chapterStartsSec = state.chapterStartsSec,
            onSeek = actions.onSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        DualTime(state)

        Spacer(Modifier.height(18.dp))
        TransportRow(state, actions)

        Spacer(Modifier.height(20.dp))
        // Speed control sits apart from the transport (keeps play centered). The filled-tonal pill
        // reads as a deliberate control; tapping it opens the shared granular speed sheet (any 0.05×
        // step in 0.5–3.0×) — the same control as the Read Aloud mini-player.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlaybackSpeedControl(
                speed = state.speed,
                onSpeedChange = actions.onSpeedChange,
                tagPrefix = "audiobook",
            ) { onClick ->
                FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(PlaybackSpeed.label(state.speed), style = MaterialTheme.typography.titleSmall)
                }
            }
            Text("Speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TransportRow(state: PlayerSurfaceState, actions: PlayerSurfaceActions) {
    // Comfortable sizing: secondary controls at a 56dp touch target with 30dp glyphs (up from the
    // 48dp/24dp default) and a 60dp primary play circle with a 32dp glyph, separated by 10dp gaps.
    val secondaryButton = 56.dp
    val secondaryIcon = 30.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onRewind, modifier = Modifier.size(secondaryButton)) {
            Icon(painterResource(R.drawable.ic_replay_15), contentDescription = "Rewind 15 seconds", modifier = Modifier.size(secondaryIcon))
        }
        Spacer(Modifier.size(10.dp))
        IconButton(onClick = actions.onPreviousChapter, enabled = state.canPreviousChapter, modifier = Modifier.size(secondaryButton)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter", modifier = Modifier.size(secondaryIcon))
        }
        Spacer(Modifier.size(10.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(60.dp),
        ) {
            IconButton(onClick = actions.onTogglePlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        IconButton(onClick = actions.onNextChapter, enabled = state.canNextChapter, modifier = Modifier.size(secondaryButton)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(secondaryIcon))
        }
        Spacer(Modifier.size(10.dp))
        IconButton(onClick = actions.onForward, modifier = Modifier.size(secondaryButton)) {
            Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(secondaryIcon))
        }
    }
}

@Composable
private fun DualTime(state: PlayerSurfaceState) {
    // Elapsed on the left, remaining (negative) on the right — the standard player convention.
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatHms(state.positionSec), style = MaterialTheme.typography.bodySmall, color = muted)
        Text("-${formatHms((state.durationSec - state.positionSec).coerceAtLeast(0.0))}", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** Continuous draggable seek track with chapter-boundary ticks and a vertical playhead (prototype 1b). */
@Composable
private fun ChapterSeekBar(
    positionSec: Double,
    durationSec: Double,
    chapterStartsSec: List<Double>,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val tickColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .height(24.dp)
            .pointerInput(durationSec) {
                detectTapGestures { offset -> if (durationSec > 0) onSeek(durationSec * (offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(durationSec) {
                detectHorizontalDragGestures { change, _ ->
                    if (durationSec > 0) onSeek(durationSec * (change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(9.dp)) {
            val w = size.width
            val h = size.height
            val frac = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0).toFloat() else 0f
            // base track
            drawRoundRect(color = track, size = size, cornerRadius = CornerRadius(h / 2, h / 2))
            // filled portion
            drawRoundRect(
                color = accent,
                size = Size(w * frac, h),
                cornerRadius = CornerRadius(h / 2, h / 2),
            )
            // chapter boundary ticks
            if (durationSec > 0) {
                chapterStartsSec.forEach { start ->
                    val x = (start / durationSec).toFloat().coerceIn(0f, 1f) * w
                    drawRect(color = tickColor, topLeft = Offset(x - 1f, 0f), size = Size(2f, h))
                }
            }
            // vertical playhead
            val px = (w * frac).coerceIn(0f, w)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(px - 2f, -h * 0.7f),
                size = Size(4f, h * 2.4f),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

private fun formatHms(totalSec: Double): String {
    val s = totalSec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
