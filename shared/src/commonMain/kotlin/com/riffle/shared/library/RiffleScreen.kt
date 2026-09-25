package com.riffle.shared.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.feature.designsystem.TestTags
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.CoverImage
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.RiffleViewModel
import com.riffle.shared.FilteredBooksHost
import com.riffle.shared.LibraryNav
import com.riffle.shared.ReaderHost
import com.riffle.shared.openItemForReading
import org.koin.compose.koinInject

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
    val tabs = listOf("In Progress", "To Read", "Annotations")

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8F8F8))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicText(
                text = "☰",
                modifier = Modifier
                    .testTag(TestTags.NAV_DRAWER_TOGGLE)
                    .clickable { onOpenDrawer() }
                    .padding(end = 16.dp),
                style = TextStyle(fontSize = 18.sp),
            )
            BasicText(
                text = "Riffle",
                style = TextStyle(fontSize = 18.sp),
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                val tabTag = when (index) {
                    0 -> TestTags.NAV_TAB_IN_PROGRESS
                    1 -> TestTags.NAV_TAB_TO_READ
                    else -> TestTags.NAV_TAB_ANNOTATIONS
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tabTag)
                        .background(if (selectedTab == index) Color(0xFFE0E0E0) else Color.Transparent)
                        .clickable { selectedTab = index }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                ) {
                    BasicText(text = title, style = TextStyle(fontSize = 13.sp))
                }
            }
        }
        if (isOffline) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFBBDEFB))
                    .padding(vertical = 6.dp, horizontal = 16.dp),
            ) {
                BasicText(
                    text = "Offline — showing cached data",
                    style = TextStyle(fontSize = 12.sp),
                )
            }
        }
        val openDetail: (sourceId: String, itemId: String) -> Unit = { sourceId, itemId ->
            nav = LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null })
        }
        // `RiffleViewModel.authTokenMap` is documented as "sourceId -> auth token for
        // authenticated cover image loading" and had no iOS caller at all, because nothing on
        // iOS loaded a cover. This screen spans sources, so each row resolves its own.
        val tokenFor: (String) -> String = { sourceId -> viewModel.authTokenMap[sourceId].orEmpty() }
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

@Composable
private fun IosInProgressTab(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    tokenFor: (String) -> String,
    onItemSelected: (LibraryItem) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (inProgress.isEmpty() && continueSeries.isEmpty()) {
            item {
                BasicText(
                    text = "No books in progress",
                    style = TextStyle(fontSize = 15.sp, color = Color.Gray),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (inProgress.isNotEmpty()) {
            item { SectionLabel("In Progress") }
            items(inProgress, key = { "${it.sourceId}_${it.id}" }) { ItemRow(it, tokenFor, onItemSelected) }
        }
        if (continueSeries.isNotEmpty()) {
            item { SectionLabel("Continue Series") }
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
        BasicText(
            text = "No books in your to-read list",
            style = TextStyle(fontSize = 15.sp, color = Color.Gray),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { "${it.sourceId}_${it.id}" }) { ItemRow(it, tokenFor, onItemSelected) }
    }
}

@Composable
private fun SectionLabel(title: String) {
    BasicText(
        text = title,
        style = TextStyle(fontSize = 13.sp, color = Color.Gray),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
        // The row used to be text only — no cover, not even the placeholder.
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
            BasicText(text = item.title, style = TextStyle(fontSize = 15.sp))
            if (item.author.isNotEmpty()) {
                BasicText(text = item.author, style = TextStyle(fontSize = 13.sp, color = Color.Gray))
            }
        }
    }
}
