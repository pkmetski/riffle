package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredBooksScreen(
    onItemSelected: (com.riffle.core.domain.LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: FilteredBooksViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(facetTitle(viewModel.facetType, viewModel.facetValue), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(top = padding.calculateTopPadding())) {
            if (isOffline) {
                OfflineBanner()
            }
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books found")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(coverGridMinCellSize()),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        Box(modifier = Modifier.padding(4.dp)) {
                            BookCoverTile(item = item, token = viewModel.authToken, onClick = { onItemSelected(item) })
                        }
                    }
                }
            }
        }
    }
}
