package com.riffle.shared.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.models.LibraryItem
import com.riffle.shared.library.DefaultCoverPlaceholder
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Suppress("ktlint:standard:function-naming")
@Composable
actual fun AudiobookPlayerScreen(item: LibraryItem, onBack: () -> Unit) {
    val vm: IosAudiobookPlayerViewModel = koinInject(
        parameters = { parametersOf(item.id, item.sourceId.ifEmpty { null }) },
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(state.title, state.positionSec) {
        if (!state.loading && !state.failed) {
            vm.updateNowPlaying(
                title = state.title.ifEmpty { item.title },
                author = state.author.ifEmpty { item.author },
                coverUrl = state.coverUrl,
            )
        }
    }

    DisposableEffect(item.id) { onDispose {} }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.text.BasicText(
                text = "← Back",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF6650A4),
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        Spacer(Modifier.height(16.dp))

        when {
            state.loading -> LoadingContent()
            state.failed -> FailedContent(onBack = onBack)
            else -> PlayerContent(
                state = state,
                itemTitle = item.title,
                itemAuthor = item.author,
                onTogglePlayPause = { vm.togglePlayPause() },
                onSeek = { vm.seekTo(it) },
                onPreviousChapter = { vm.previousChapter() },
                onNextChapter = { vm.nextChapter() },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.text.BasicText(
            text = "Loading…",
            style = TextStyle(fontSize = 16.sp, color = Color(0xFF888888)),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FailedContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.text.BasicText(
                text = "Could not open audiobook",
                style = TextStyle(fontSize = 16.sp, color = Color(0xFF888888)),
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.text.BasicText(
                text = "← Back",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF6650A4),
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PlayerContent(
    state: IosAudiobookPlayerState,
    itemTitle: String,
    itemAuthor: String,
    onTogglePlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Cover
        Box(
            modifier = Modifier
                .width(180.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .align(Alignment.CenterHorizontally),
        ) {
            DefaultCoverPlaceholder(isAudiobook = true)
        }

        Spacer(Modifier.height(20.dp))

        // Title / author
        androidx.compose.foundation.text.BasicText(
            text = state.title.ifEmpty { itemTitle },
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicText(
            text = state.author.ifEmpty { itemAuthor },
            style = TextStyle(fontSize = 14.sp, color = Color(0xFF666666)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        state.currentChapterTitle?.let { ch ->
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.text.BasicText(
                text = ch,
                style = TextStyle(fontSize = 12.sp, color = Color(0xFF999999)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Seek bar (tap-to-seek approximation via fraction)
        ProgressBar(
            positionSec = state.positionSec,
            durationSec = state.durationSec,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(8.dp))

        // Time labels
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            androidx.compose.foundation.text.BasicText(
                text = formatDuration(state.positionSec),
                style = TextStyle(fontSize = 12.sp, color = Color(0xFF666666)),
            )
            androidx.compose.foundation.text.BasicText(
                text = formatDuration(state.durationSec),
                style = TextStyle(fontSize = 12.sp, color = Color(0xFF666666)),
            )
        }

        Spacer(Modifier.height(20.dp))

        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                label = "⏮",
                enabled = state.canPreviousChapter,
                onClick = onPreviousChapter,
            )
            Spacer(Modifier.width(24.dp))
            PlayPauseButton(isPlaying = state.isPlaying, onClick = onTogglePlayPause)
            Spacer(Modifier.width(24.dp))
            ControlButton(
                label = "⏭",
                enabled = state.canNextChapter,
                onClick = onNextChapter,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Chapter list
        if (state.chapters.isNotEmpty()) {
            androidx.compose.foundation.text.BasicText(
                text = "Chapters",
                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(state.chapters) { ch ->
                    val isCurrent = ch.index == state.currentChapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(ch.startSec) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isCurrent) Color(0xFF6650A4) else Color.Transparent),
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = ch.title,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) Color(0xFF6650A4) else Color.Unspecified,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ProgressBar(
    positionSec: Double,
    durationSec: Double,
    onSeek: (Double) -> Unit,
) {
    val fraction = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFE0E0E0))
            .clickable { onSeek(durationSec * 0.5) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(Color(0xFF6650A4)),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF6650A4))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            text = if (isPlaying) "⏸" else "▶",
            style = TextStyle(fontSize = 28.sp, color = Color.White),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ControlButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFFEAE0F8) else Color(0xFFF5F5F5))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            text = label,
            style = TextStyle(
                fontSize = 20.sp,
                color = if (enabled) Color(0xFF6650A4) else Color(0xFFBDBDBD),
            ),
        )
    }
}

private fun formatDuration(totalSec: Double): String {
    val secs = totalSec.toLong().coerceAtLeast(0)
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    fun pad2(n: Long) = if (n < 10) "0$n" else "$n"
    return if (h > 0) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}
