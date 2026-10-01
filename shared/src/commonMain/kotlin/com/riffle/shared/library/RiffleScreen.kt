package com.riffle.shared.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.CoverImage
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_annotations
import com.riffle.feature.designsystem.generated.resources.ui_no_books_in_progress
import com.riffle.feature.designsystem.generated.resources.ui_no_books_in_to_read
import com.riffle.feature.designsystem.generated.resources.ui_to_read
import com.riffle.feature.designsystem.generated.resources.ui_offline_showing_cached_data
import com.riffle.feature.designsystem.generated.resources.ui_open_menu
import com.riffle.feature.designsystem.generated.resources.ui_section_continue_series
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.RiffleViewModel
import com.riffle.shared.FilteredBooksHost
import com.riffle.shared.LibraryNav
import com.riffle.shared.ReaderHost
import com.riffle.shared.openItemForReading
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiffleScreen(
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<RiffleViewModel>()
    val inProgress by viewModel.inProgress.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()
    val toRead by viewModel.toRead.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    // Same destination vocabulary the per-library host uses, so the hub reaches the readers by
    // the same route instead of dead-ending on the detail sheet.
    var nav by remember { mutableStateOf<LibraryNav?>(null) }
    val applicationScope = koinInject<ApplicationScope>()
    val recordItemOpened = koinInject<RecordItemOpened>()

    when (val current = nav) {
        is LibraryNav.ItemDetail -> {
            LibraryItemDetailScreen(
                itemId = current.itemId,
                sourceId = current.sourceId,
                onBack = { nav = null },
                // Stay on the sheet when the format has no iOS reader rather than dismissing it.
                onRead = { item ->
                    openItemForReading(item, applicationScope, recordItemOpened::invoke)?.let { nav = it }
                },
                onFacetSelected = { facetLibraryId, facet, value ->
                    nav = LibraryNav.FilteredBooks(facetLibraryId, facet, value)
                },
            )
            return
        }
        is LibraryNav.FilteredBooks -> {
            FilteredBooksHost(
                destination = current,
                onBack = { nav = null },
                onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
            )
            return
        }
        is LibraryNav.ReaderDestination -> {
            // No playlist context ever reaches the hub: every destination here comes from
            // `openItemForReading`, which builds `AudiobookPlayer(item)` with both playlist
            // fields null, so `PlaylistAdvance` cannot be emitted. Same reasoning as Android's
            // defaulted `onPlaylistAdvance` on its non-playlist player entry points.
            ReaderHost(destination = current, onBack = { nav = null }, onPlaylistAdvance = { _, _ -> })
            return
        }
        else -> Unit
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val inProgressLabel = stringResource(Res.string.ui_section_in_progress)
    val toReadLabel = stringResource(Res.string.ui_to_read)
    val annotationsLabel = stringResource(Res.string.ui_annotations)

    // `RiffleViewModel.authTokenMap` is documented as "sourceId -> auth token for authenticated
    // cover image loading" and had no iOS caller at all, because nothing on iOS loaded a cover.
    // This screen spans sources, so each row resolves its own.
    val tokenFor: (String) -> String = { sourceId -> viewModel.authTokenMap[sourceId].orEmpty() }
    val openDetail: (sourceId: String, itemId: String) -> Unit = { sourceId, itemId ->
        nav = LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null })
    }

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
                        Icon(RiffleIcons.Menu, contentDescription = stringResource(Res.string.ui_open_menu))
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isOffline) {
                Text(
                    text = stringResource(Res.string.ui_offline_showing_cached_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            when (selectedTab) {
                0 -> IosInProgressTab(inProgress, continueSeries, tokenFor) { openDetail(it.sourceId, it.id) }
                1 -> IosToReadTab(toRead, tokenFor) { openDetail(it.sourceId, it.id) }
                // Same list the per-library Annotations tab renders — one composable, two hosts.
                else -> AnnotationsTabContent(
                    state = AnnotationsListUiState(loading = false, books = annotations),
                    tokenFor = tokenFor,
                    onBookSelected = openDetail,
                )
            }
        }
    }
}

@Composable
private fun IosInProgressTab(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    tokenFor: (String) -> String,
    onItemSelected: (LibraryItem) -> Unit,
) {
    val inProgressLabel = stringResource(Res.string.ui_section_in_progress)
    val continueSeriesLabel = stringResource(Res.string.ui_section_continue_series)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (inProgress.isEmpty() && continueSeries.isEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.ui_no_books_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (inProgress.isNotEmpty()) {
            item { SectionHeader("$inProgressLabel (${inProgress.size})") }
            items(inProgress, key = { "${it.sourceId}_${it.id}" }) { ItemRow(it, tokenFor, onItemSelected) }
        }
        if (continueSeries.isNotEmpty()) {
            item { SectionHeader("$continueSeriesLabel (${continueSeries.size})") }
            items(continueSeries, key = { "cs_${it.sourceId}_${it.id}" }) { ItemRow(it, tokenFor, onItemSelected) }
        }
    }
}

@Composable
private fun IosToReadTab(
    items: List<LibraryItem>,
    tokenFor: (String) -> String,
    onItemSelected: (LibraryItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(Res.string.ui_no_books_in_to_read),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { "${it.sourceId}_${it.id}" }) { ItemRow(it, tokenFor, onItemSelected) }
    }
}

@Composable
private fun ItemRow(item: LibraryItem, tokenFor: (String) -> String, onClick: (LibraryItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(item) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) {
            CoverImage(
                url = item.coverUrl,
                token = tokenFor(item.sourceId),
                // The clickable Row merges its descendants and already announces title + author.
                contentDescription = null,
                isAudiobook = item.isAudiobookOnly,
                modifier = Modifier.fillMaxSize(),
                instrumentationKey = item.id,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = item.title, style = MaterialTheme.typography.bodyLarge)
            if (item.author.isNotEmpty()) {
                Text(text = item.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
