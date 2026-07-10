package com.riffle.app.feature.settings.debug

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.logging.LogChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app viewer for [InMemoryLogBuffer]. Reverse-chronological (newest first), channel-filter
 * chips, and a "Share" action that hands the current view off via FileProvider so the log can be
 * emailed / messaged without adb. This is what makes decoration-path diagnostics accessible on
 * devices that don't support wireless debugging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onNavigateBack: () -> Unit,
    viewModel: DebugLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val context = LocalContext.current
    val activeChannels = remember { mutableStateOf<Set<LogChannel>>(emptySet()) }

    val filtered = remember(entries, activeChannels.value) {
        if (activeChannels.value.isEmpty()) entries else entries.filter { it.channel in activeChannels.value }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val intent = viewModel.buildShareIntent(activeChannels.value.takeIf { it.isNotEmpty() })
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "Share debug log"))
                        }
                    }) {
                        Text("Share")
                    }
                    TextButton(onClick = { viewModel.clear() }) {
                        Text("Clear")
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
                text = "${filtered.size}/${entries.size} entries (buffer holds ${InMemoryLogBuffer.CAPACITY})",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (entries.isEmpty()) "No log entries yet" else "No entries match the current filter",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                // Reverse so newest is at the top and remains visible without manual scrolling
                // when new entries land.
                LaunchedEffect(filtered.size) {
                    if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                ) {
                    items(filtered, key = { it.seq }) { entry ->
                        LogRow(entry)
                        HorizontalDivider(color = Color(0x11000000))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: InMemoryLogBuffer.Entry) {
    val time = remember(entry.timestampMs) { TIME_FMT.format(Date(entry.timestampMs)) }
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

private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
