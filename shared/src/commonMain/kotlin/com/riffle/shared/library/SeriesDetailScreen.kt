package com.riffle.shared.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.SeriesDetailViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun SeriesDetailScreen(
    seriesId: String,
    libraryId: String,
    seriesName: String,
    onItemSelected: (LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SeriesDetailViewModel = koinInject { parametersOf(seriesId, libraryId) },
) {
    val items by viewModel.items.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("No books in this series")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 60.dp,
                    bottom = 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    BookCoverTile(
                        item = item,
                        modifier = Modifier.aspectRatio(2f / 3f),
                        onClick = { onItemSelected(item) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "←",
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clickable(onClick = onNavigateBack),
                style = TextStyle(fontSize = 20.sp),
            )
            BasicText(
                text = seriesName,
                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            )
        }
    }
}
