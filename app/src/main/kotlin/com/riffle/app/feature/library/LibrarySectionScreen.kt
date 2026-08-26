package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.riffle.core.models.LibraryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySectionScreen(
    sectionType: LibrarySectionType,
    onItemSelected: (LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: LibrarySectionViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sectionType.displayName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalCoversAreSquare provides coversAreSquare) {
            BookGrid(
                items = items,
                token = viewModel.authToken,
                onItemSelected = onItemSelected,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun BookGrid(
    items: List<LibraryItem>,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    contentPadding: PaddingValues,
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_items))
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCellSize()),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.padding(4.dp)) {
                BookCoverTile(item = item, token = token, onClick = { onItemSelected(item) })
            }
        }
    }
}
