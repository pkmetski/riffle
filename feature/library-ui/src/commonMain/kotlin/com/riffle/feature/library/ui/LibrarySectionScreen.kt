package com.riffle.feature.library.ui

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
import androidx.compose.ui.platform.testTag
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.BookGrid
import com.riffle.feature.designsystem.LocalCoversAreSquare
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.generated.resources.ui_all_books
import com.riffle.feature.designsystem.generated.resources.ui_collections
import com.riffle.feature.designsystem.generated.resources.ui_section_completed
import com.riffle.feature.designsystem.generated.resources.ui_section_continue_series
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.designsystem.generated.resources.ui_section_recently_added
import com.riffle.feature.designsystem.generated.resources.ui_series
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.LibrarySectionViewModel
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import com.riffle.feature.designsystem.generated.resources.Res as DsRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySectionScreen(
    libraryId: String,
    sectionType: LibrarySectionType,
    onItemSelected: (LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: LibrarySectionViewModel = koinInject { parametersOf(libraryId, sectionType) },
) {
    val items by viewModel.items.collectAsState()
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(sectionType.titleStringResource())) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag(TestTags.LIBRARY_SECTION_BACK),
                    ) {
                        Icon(LibraryUiGlyphs.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
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

/** Maps each [LibrarySectionType] to its localised title from composeResources. */
fun LibrarySectionType.titleStringResource(): StringResource = when (this) {
    LibrarySectionType.IN_PROGRESS -> DsRes.string.ui_section_in_progress
    LibrarySectionType.FINISHED -> DsRes.string.ui_section_completed
    LibrarySectionType.RECENTLY_ADDED -> DsRes.string.ui_section_recently_added
    LibrarySectionType.CONTINUE_SERIES -> DsRes.string.ui_section_continue_series
    LibrarySectionType.SERIES -> DsRes.string.ui_series
    LibrarySectionType.COLLECTIONS -> DsRes.string.ui_collections
    LibrarySectionType.ALL_BOOKS -> DsRes.string.ui_all_books
}
