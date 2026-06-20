package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
fun AnnotationSearchResultsScreen(
    onNavigateBack: () -> Unit,
    onAnnotationSelected: (AnnotationSearchResult) -> Unit,
    onAudiobookBookmarkSelected: (AudiobookBookmarkSearchResult) -> Unit,
    viewModel: AnnotationSearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val bookmarkResults by viewModel.bookmarkResults.collectAsState()
    val token by viewModel.authToken.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotations · '${viewModel.query}'") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (results.isEmpty() && bookmarkResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No annotations for '${viewModel.query}'",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(results, key = { "anno_${it.annotation.id}" }) { result ->
                AnnotationResultRow(
                    result = result,
                    token = token,
                    onClick = { onAnnotationSelected(result) },
                )
            }
            items(bookmarkResults, key = { "abm_${it.bookmark.id}" }) { result ->
                AudiobookBookmarkResultRow(
                    result = result,
                    token = token,
                    onClick = { onAudiobookBookmarkSelected(result) },
                )
            }
        }
    }
}
