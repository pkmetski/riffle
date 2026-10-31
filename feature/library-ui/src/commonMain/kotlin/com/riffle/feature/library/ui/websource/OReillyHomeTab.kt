package com.riffle.feature.library.ui.websource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.catalog.CatalogItem
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_no_recent_reading_activity
import com.riffle.feature.designsystem.generated.resources.ui_section_completed
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.source.ui.websource.OReillyHomeViewModel
import com.riffle.feature.source.ui.websource.WebSourceCatalogItemCard
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Home tab content for the O'Reilly source, showing "Continue Reading" (in-progress items)
 * and "Completed" sections populated directly from the user's reading history on
 * `learning.oreilly.com`.
 *
 * Lives in `:feature:library-ui` (android + iOS topology) so both hosts render the same
 * composable — same dependency direction as [UnboundedBrowseScreen].
 */
@Composable
fun OReillyHomeTab(
    onOpenDetail: (itemId: String) -> Unit,
) {
    val viewModel: OReillyHomeViewModel = koinInject()

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    val completed by viewModel.completed.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()

    val currentOnOpenDetail by rememberUpdatedState(onOpenDetail)
    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> currentOnOpenDetail(event.itemId) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            error != null -> Text(
                text = error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            isEmpty -> Text(
                text = stringResource(Res.string.ui_no_recent_reading_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (inProgress.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(Res.string.ui_section_in_progress))
                        }
                        item {
                            OReillyHistoryRow(
                                items = inProgress,
                                onItemClick = viewModel::openDetail,
                            )
                        }
                    }
                    if (completed.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(Res.string.ui_section_completed))
                        }
                        item {
                            OReillyHistoryRow(
                                items = completed,
                                onItemClick = viewModel::openDetail,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OReillyHistoryRow(
    items: List<CatalogItem>,
    onItemClick: (CatalogItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.width(120.dp)) {
                WebSourceCatalogItemCard(
                    item = item,
                    isAudio = item.hasAudio,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}
