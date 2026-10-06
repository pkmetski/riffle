package com.riffle.feature.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.BookSectionGrid
import com.riffle.feature.designsystem.LocalCoverGridScale
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.generated.resources.ui_annotations
import com.riffle.feature.designsystem.generated.resources.ui_open_menu
import com.riffle.feature.designsystem.generated.resources.ui_section_continue_series
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.designsystem.generated.resources.ui_to_read
import com.riffle.feature.designsystem.pinchCoverZoom
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.source.ui.OfflineBanner
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.designsystem.generated.resources.Res as DsRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiffleScreen(
    viewModel: RiffleViewModel,
    onOpenDrawer: () -> Unit,
    onItemSelected: (sourceId: String, itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit = onItemSelected,
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()
    val toRead by viewModel.toRead.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    val sourceBadgeMap = sources.associate { it.id to sourceDisplayName(it) }
    val authTokenMap = viewModel.authTokenMap

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val (liveCoverScale, onCoverScaleChange) = rememberLivePersistedScale(
        flow = viewModel.coverGridScale,
        onPersist = viewModel::setCoverGridScale,
    )

    val inProgressLabel = stringResource(DsRes.string.ui_section_in_progress)
    val toReadLabel = stringResource(DsRes.string.ui_to_read)
    val annotationsLabel = stringResource(DsRes.string.ui_annotations)

    CompositionLocalProvider(LocalCoverGridScale provides liveCoverScale) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "RIFFLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag(TestTags.NAV_DRAWER_TOGGLE),
                        ) {
                            Icon(RiffleIcons.Menu, contentDescription = stringResource(DsRes.string.ui_open_menu))
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(RiffleIcons.Home, contentDescription = inProgressLabel) },
                        modifier = Modifier.testTag(TestTags.NAV_TAB_IN_PROGRESS),
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = toReadLabel) },
                        modifier = Modifier.testTag(TestTags.NAV_TAB_TO_READ),
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(RiffleIcons.Annotations, contentDescription = annotationsLabel) },
                        modifier = Modifier.testTag(TestTags.NAV_TAB_ANNOTATIONS),
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .pinchCoverZoom(liveCoverScale, onCoverScaleChange),
            ) {
                if (isOffline) {
                    OfflineBanner()
                }
                when (selectedTab) {
                    0 -> RiffleInProgressTab(
                        inProgress = inProgress,
                        continueSeries = continueSeries,
                        sourceBadgeMap = sourceBadgeMap,
                        authTokenMap = authTokenMap,
                        onItemSelected = { onItemSelected(it.sourceId, it.id) },
                    )
                    1 -> RiffleToReadTab(
                        items = toRead,
                        sourceBadgeMap = sourceBadgeMap,
                        authTokenMap = authTokenMap,
                        onItemSelected = { onItemSelected(it.sourceId, it.id) },
                    )
                    else -> AnnotationsListScreen(
                        state = AnnotationsListUiState(loading = false, books = annotations),
                        onBookSelected = onAnnotatedBookClick,
                        tokenMap = authTokenMap,
                    )
                }
            }
        }
    }
}

@Composable
private fun RiffleInProgressTab(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    sourceBadgeMap: Map<String, String>,
    authTokenMap: Map<String, String>,
    onItemSelected: (LibraryItem) -> Unit,
) {
    val inProgressLabel = stringResource(DsRes.string.ui_section_in_progress)
    val continueSeriesLabel = stringResource(DsRes.string.ui_section_continue_series)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (inProgress.isNotEmpty()) {
            item(key = "header_in_progress") {
                SectionHeader("$inProgressLabel (${inProgress.size})")
            }
            item(key = "grid_in_progress") {
                BookSectionGrid(
                    items = inProgress,
                    token = "",
                    tokenMap = authTokenMap,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    sourceBadgeProvider = { sourceBadgeMap[it.sourceId] },
                )
            }
        }
        if (continueSeries.isNotEmpty()) {
            item(key = "header_continue_series") {
                SectionHeader("$continueSeriesLabel (${continueSeries.size})")
            }
            item(key = "grid_continue_series") {
                BookSectionGrid(
                    items = continueSeries,
                    token = "",
                    tokenMap = authTokenMap,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    showSeriesBadge = true,
                    sourceBadgeProvider = { sourceBadgeMap[it.sourceId] },
                )
            }
        }
    }
}

@Composable
private fun RiffleToReadTab(
    items: List<LibraryItem>,
    sourceBadgeMap: Map<String, String>,
    authTokenMap: Map<String, String>,
    onItemSelected: (LibraryItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (items.isNotEmpty()) {
            item(key = "header_to_read") {
                SectionHeader(stringResource(DsRes.string.ui_to_read))
            }
            item(key = "grid_to_read") {
                BookSectionGrid(
                    items = items,
                    token = "",
                    tokenMap = authTokenMap,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    sourceBadgeProvider = { sourceBadgeMap[it.sourceId] },
                )
            }
        }
    }
}

/** Collects [flow] into a live local copy that immediately reflects gestures. */
@Composable
internal fun rememberLivePersistedScale(
    flow: kotlinx.coroutines.flow.StateFlow<Float>,
    onPersist: (Float) -> Unit,
): Pair<Float, (Float) -> Unit> {
    val persisted by flow.collectAsState()
    var live by remember { mutableFloatStateOf(persisted) }
    LaunchedEffect(persisted) { live = persisted }
    return live to { value: Float ->
        live = value
        onPersist(value)
    }
}
