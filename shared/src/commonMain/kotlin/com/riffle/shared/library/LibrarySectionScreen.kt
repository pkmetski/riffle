package com.riffle.shared.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.testTag
import com.riffle.feature.designsystem.BookGrid
import com.riffle.feature.designsystem.LocalCoversAreSquare
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_all_books
import com.riffle.feature.designsystem.generated.resources.ui_collections
import com.riffle.feature.designsystem.generated.resources.ui_section_completed
import com.riffle.feature.designsystem.generated.resources.ui_section_continue_series
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.designsystem.generated.resources.ui_section_recently_added
import com.riffle.feature.designsystem.generated.resources.ui_series
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.LibrarySectionViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun LibrarySectionScreen(
    libraryId: String,
    sectionType: LibrarySectionType,
    onBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    viewModel: LibrarySectionViewModel = koinInject { parametersOf(libraryId, sectionType) },
) {
    val items by viewModel.items.collectAsState()
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalCoversAreSquare provides coversAreSquare) {
            BookGrid(
                items = items,
                token = viewModel.authToken,
                onItemSelected = onItemSelected,
                // Room for the overlaid header row above the first cover.
                contentPadding = PaddingValues(top = 52.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "←",
                modifier = Modifier
                    .padding(end = 12.dp)
                    .testTag(TestTags.LIBRARY_SECTION_BACK)
                    .clickable(onClick = onBack),
                style = TextStyle(fontSize = 20.sp),
            )
            BasicText(
                text = when (sectionType) {
                    LibrarySectionType.IN_PROGRESS -> stringResource(Res.string.ui_section_in_progress)
                    LibrarySectionType.FINISHED -> stringResource(Res.string.ui_section_completed)
                    LibrarySectionType.RECENTLY_ADDED -> stringResource(Res.string.ui_section_recently_added)
                    LibrarySectionType.CONTINUE_SERIES -> stringResource(Res.string.ui_section_continue_series)
                    LibrarySectionType.SERIES -> stringResource(Res.string.ui_series)
                    LibrarySectionType.COLLECTIONS -> stringResource(Res.string.ui_collections)
                    LibrarySectionType.ALL_BOOKS -> stringResource(Res.string.ui_all_books)
                },
                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            )
        }
    }
}
