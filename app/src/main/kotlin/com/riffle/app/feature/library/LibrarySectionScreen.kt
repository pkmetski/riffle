package com.riffle.app.feature.library

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.BookGrid
import com.riffle.feature.designsystem.LocalCoversAreSquare
import com.riffle.feature.library.LibrarySectionType
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySectionScreen(
    sectionType: LibrarySectionType,
    onItemSelected: (LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: com.riffle.feature.library.LibrarySectionViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(sectionType.titleResId())) },
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

