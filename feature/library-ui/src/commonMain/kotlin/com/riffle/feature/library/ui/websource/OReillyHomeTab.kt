package com.riffle.feature.library.ui.websource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.DefaultCoverPlaceholder
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_no_recent_reading_activity
import com.riffle.feature.designsystem.generated.resources.ui_section_completed
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.source.ui.websource.OReillyHomeViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun OReillyHomeTab(
    libraryId: String,
    onOpenDetail: (itemId: String) -> Unit,
) {
    val viewModel: OReillyHomeViewModel = koinInject { parametersOf(libraryId) }
    val inProgress by viewModel.inProgress.collectAsState()
    val completed by viewModel.completed.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isEmpty -> Text(
                text = stringResource(Res.string.ui_no_recent_reading_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (inProgress.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(Res.string.ui_section_in_progress))
                        }
                        item {
                            OReillyHistoryRow(items = inProgress, onItemClick = { onOpenDetail(it.id) })
                        }
                    }
                    if (completed.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(Res.string.ui_section_completed))
                        }
                        item {
                            OReillyHistoryRow(items = completed, onItemClick = { onOpenDetail(it.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OReillyHistoryRow(
    items: List<LibraryItem>,
    onItemClick: (LibraryItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.width(120.dp)) {
                OReillyItemCard(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
private fun OReillyItemCard(item: LibraryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ratio = if (item.isAudiobookOnly) 1f else (2f / 3f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(MaterialTheme.shapes.small),
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DefaultCoverPlaceholder(
                    isAudiobook = item.isAudiobookOnly,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.readingProgress > 0f) {
                LinearProgressIndicator(
                    progress = { item.readingProgress },
                    modifier = Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.BottomCenter),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
