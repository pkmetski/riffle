package com.riffle.feature.settings.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.logging.LogChannel
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_back
import com.riffle.feature.settings.ui.generated.resources.ui_clear
import com.riffle.feature.settings.ui.generated.resources.ui_debug_entries_count
import com.riffle.feature.settings.ui.generated.resources.ui_debug_logs
import com.riffle.feature.settings.ui.generated.resources.ui_no_entries_match_current_filter
import com.riffle.feature.settings.ui.generated.resources.ui_no_log_entries_yet
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * In-app viewer for [InMemoryLogBuffer] on iOS. Reverse-chronological (newest first),
 * channel-filter chips, and a Clear action. Unlike the Android version there is no Share
 * action — sharing via UIActivityViewController requires UIKit interop that is deferred.
 *
 * Reads [InMemoryLogBuffer] directly rather than through an AndroidViewModel because
 * AndroidViewModel is not available in iosMain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosDebugLogScreen(
    onNavigateBack: () -> Unit,
    logBuffer: InMemoryLogBuffer = koinInject(),
) {
    val entries by logBuffer.entries.collectAsState()
    val activeChannels = remember { mutableStateOf<Set<LogChannel>>(emptySet()) }

    val filtered = remember(entries, activeChannels.value) {
        if (activeChannels.value.isEmpty()) entries else entries.filter { it.channel in activeChannels.value }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_debug_logs)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
                actions = {
                    TextButton(onClick = { logBuffer.clear() }) {
                        Text(stringResource(Res.string.ui_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogChannel.entries.forEach { ch ->
                    val selected = ch in activeChannels.value
                    FilterChip(
                        selected = selected,
                        onClick = {
                            activeChannels.value = if (selected) activeChannels.value - ch else activeChannels.value + ch
                        },
                        label = { Text(ch.tag) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Text(
                text = stringResource(
                    Res.string.ui_debug_entries_count,
                    filtered.size,
                    entries.size,
                    InMemoryLogBuffer.CAPACITY,
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (entries.isEmpty()) {
                            stringResource(Res.string.ui_no_log_entries_yet)
                        } else {
                            stringResource(Res.string.ui_no_entries_match_current_filter)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                val newestFirst = remember(filtered) { filtered.asReversed() }
                LaunchedEffect(newestFirst.size) {
                    if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(newestFirst, key = { it.seq }) { entry ->
                        IosLogRow(entry)
                        HorizontalDivider(color = Color(0x11000000))
                    }
                }
            }
        }
    }
}

@Composable
private fun IosLogRow(entry: InMemoryLogBuffer.Entry) {
    val time = remember(entry.timestampMs) {
        val dt = Instant.fromEpochMilliseconds(entry.timestampMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        "%02d:%02d:%02d.%03d".format(dt.hour, dt.minute, dt.second, entry.timestampMs % 1000)
    }
    val (bg, fg) = when (entry.level) {
        InMemoryLogBuffer.Entry.Level.D -> Color.Transparent to MaterialTheme.colorScheme.onSurface
        InMemoryLogBuffer.Entry.Level.W -> Color(0x33FFC107) to MaterialTheme.colorScheme.onSurface
        InMemoryLogBuffer.Entry.Level.E -> Color(0x33F44336) to MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$time  ${entry.level.name}  [${entry.channel.tag}]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = fg.copy(alpha = 0.7f),
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = fg,
        )
        entry.throwableSummary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = fg.copy(alpha = 0.8f),
            )
        }
    }
}
