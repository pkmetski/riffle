package com.riffle.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.FacetType
import com.riffle.feature.library.FilteredBooksViewModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the real [FilteredBooksScreen] against a real [FilteredBooksViewModel]. Excluded from
 * the JVM host-test task (Compose's UI harness needs an Android runtime) and run for real on
 * `:feature:library-ui:iosSimulatorArm64Test` — so this IS the iOS-path assertion for the facet
 * drill-in, a screen that did not exist on iOS at all (#1072 §1).
 */
@OptIn(ExperimentalTestApi::class)
class FilteredBooksRenderTest {

    private fun vm(
        facetType: FacetType = FacetType.GENRE,
        facetValue: String = "Sci-Fi",
        items: List<LibraryItem> = emptyList(),
        online: Boolean = true,
        offlineItemIds: Set<String> = emptySet(),
    ) = FilteredBooksViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                FilteredBooksViewModel.ROUTE_ARG_LIBRARY_ID to "lib-7",
                FilteredBooksViewModel.ROUTE_ARG_FACET_TYPE to facetType.name,
                FilteredBooksViewModel.ROUTE_ARG_FACET_VALUE to facetValue,
            ),
        ),
        libraryObserver = FilteredBooksFakes.libraryObserver(items),
        sourceRepository = FilteredBooksFakes.sourceRepository(),
        tokenStorage = FilteredBooksFakes.tokenStorage(),
        offlineAvailability = FilteredBooksFakes.offlineAvailability(offlineItemIds),
        connectivityObserver = FilteredBooksFakes.connectivityObserver(online),
        readaloudLinkRepository = FilteredBooksFakes.readaloudLinkRepository(),
    )

    private fun item(id: String, title: String, genres: List<String>) = LibraryItem(
        id = id,
        sourceId = "src-1",
        libraryId = "lib-7",
        title = title,
        author = "An Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        genres = genres,
    )

    @Test
    fun theFacetValueTitlesTheScreenAndOnlyMatchingBooksAreListed() = runComposeUiTest {
        setContent {
            FilteredBooksScreen(
                viewModel = vm(
                    items = listOf(
                        item("a", "Matching Book", listOf("Sci-Fi")),
                        item("b", "Other Book", listOf("Romance")),
                    ),
                ),
                labels = FilteredBooksLabels.English,
                minCellSize = 120.dp,
                onItemSelected = {},
                onNavigateBack = {},
                tileContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
            )
        }

        onNodeWithText("Sci-Fi").assertIsDisplayed()
        onNodeWithText("Matching Book").assertIsDisplayed()
        onNodeWithTag("filtered-books-grid").assertIsDisplayed()
    }

    @Test
    fun tappingATileOpensTheItem() = runComposeUiTest {
        var opened: LibraryItem? = null
        setContent {
            FilteredBooksScreen(
                viewModel = vm(items = listOf(item("a", "Matching Book", listOf("Sci-Fi")))),
                labels = FilteredBooksLabels.English,
                minCellSize = 120.dp,
                onItemSelected = { opened = it },
                onNavigateBack = {},
                tileContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
            )
        }

        onNodeWithTag("playlist-item-a").performClick()

        assertEquals("a", opened?.id)
    }

    @Test
    fun aFacetWithNoMatchesShowsTheEmptyState() = runComposeUiTest {
        setContent {
            FilteredBooksScreen(
                viewModel = vm(items = listOf(item("b", "Other Book", listOf("Romance")))),
                labels = FilteredBooksLabels.English,
                minCellSize = 120.dp,
                onItemSelected = {},
                onNavigateBack = {},
                tileContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
            )
        }

        onNodeWithText(FilteredBooksLabels.English.noBooksFound).assertIsDisplayed()
    }

    /**
     * Offline the list narrows to what is actually on the device, and the banner says so —
     * Android's behaviour, which the iOS screen must not quietly drop.
     */
    @Test
    fun offlineTheListNarrowsToLocallyAvailableBooks() = runComposeUiTest {
        setContent {
            FilteredBooksScreen(
                viewModel = vm(
                    items = listOf(
                        item("a", "Downloaded Book", listOf("Sci-Fi")),
                        item("b", "Streaming Book", listOf("Sci-Fi")),
                    ),
                    online = false,
                    offlineItemIds = setOf("a"),
                ),
                labels = FilteredBooksLabels.English,
                minCellSize = 120.dp,
                onItemSelected = {},
                onNavigateBack = {},
                tileContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
            )
        }

        onNodeWithText("Downloaded Book").assertIsDisplayed()
        onNodeWithTag("playlist-item-b").assertDoesNotExist()
    }

    @Test
    fun theBackArrowIsLabelledForScreenReaders() = runComposeUiTest {
        var back = false
        setContent {
            FilteredBooksScreen(
                viewModel = vm(),
                labels = FilteredBooksLabels.English,
                minCellSize = 120.dp,
                onItemSelected = {},
                onNavigateBack = { back = true },
                tileContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
            )
        }

        onNodeWithContentDescription(FilteredBooksLabels.English.back).performClick()

        assertEquals(true, back)
    }
}
