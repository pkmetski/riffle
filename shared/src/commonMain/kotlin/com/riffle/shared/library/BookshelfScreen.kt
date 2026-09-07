package com.riffle.shared.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.BookshelfViewModel
import com.riffle.feature.library.LibrarySectionType
import org.koin.compose.koinInject

@Composable
fun BookshelfScreen(
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<BookshelfViewModel>()
    val inProgress by viewModel.inProgress.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()
    val toRead by viewModel.toRead.collectAsState()
    val annotations by viewModel.annotations.collectAsState()

    var selectedItem by remember { mutableStateOf<LibraryItem?>(null) }
    val current = selectedItem
    if (current != null) {
        LibraryItemDetailScreen(
            itemId = current.id,
            sourceId = current.sourceId.ifEmpty { null },
            onBack = { selectedItem = null },
            onReadNotSupported = { selectedItem = null },
        )
        return
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
                    .clickable { onOpenDrawer() }
                    .padding(end = 16.dp),
                style = TextStyle(fontSize = 18.sp),
            )
            BasicText(
                text = "Bookshelf",
                style = TextStyle(fontSize = 18.sp),
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (selectedTab == index) Color(0xFFE0E0E0) else Color.Transparent)
                        .clickable { selectedTab = index }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                ) {
                    BasicText(text = title, style = TextStyle(fontSize = 13.sp))
                }
            }
        }
        when (selectedTab) {
            0 -> IosInProgressTab(inProgress, continueSeries) { selectedItem = it }
            1 -> IosToReadTab(toRead) { selectedItem = it }
            else -> IosAnnotationsTab(annotations)
        }
    }
}

@Composable
private fun IosInProgressTab(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
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
            item { SectionLabel(LibrarySectionType.IN_PROGRESS.title) }
            items(inProgress, key = { "${it.sourceId}_${it.id}" }) { ItemRow(it, onItemSelected) }
        }
        if (continueSeries.isNotEmpty()) {
            item { SectionLabel(LibrarySectionType.CONTINUE_SERIES.title) }
            items(continueSeries, key = { "cs_${it.sourceId}_${it.id}" }) { ItemRow(it, onItemSelected) }
        }
    }
}

@Composable
private fun IosToReadTab(
    items: List<LibraryItem>,
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
        items(items, key = { "${it.sourceId}_${it.id}" }) { ItemRow(it, onItemSelected) }
    }
}

@Composable
private fun IosAnnotationsTab(annotations: List<AnnotatedBook>) {
    if (annotations.isEmpty()) {
        BasicText(
            text = "No annotated books",
            style = TextStyle(fontSize = 15.sp, color = Color.Gray),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        annotations.forEach { book ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(text = book.title ?: "", style = TextStyle(fontSize = 15.sp))
                    if (book.author != null) {
                        BasicText(text = book.author, style = TextStyle(fontSize = 13.sp, color = Color.Gray))
                    }
                    BasicText(
                        text = "${book.highlightCount} highlight${if (book.highlightCount != 1) "s" else ""}",
                        style = TextStyle(fontSize = 12.sp, color = Color.Gray),
                    )
                }
            }
        }
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
private fun ItemRow(item: LibraryItem, onClick: (LibraryItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(item) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = item.title, style = TextStyle(fontSize = 15.sp))
            if (item.author != null) {
                BasicText(text = item.author, style = TextStyle(fontSize = 13.sp, color = Color.Gray))
            }
        }
    }
}
