package com.riffle.shared.source

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.feature.source.ui.websource.UnboundedBrowseContent
import com.riffle.feature.source.ui.websource.WebSourceCatalogItemCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the unbounded-catalogue grid on the iOS target.
 *
 * This is the assertion behind #1071 §17: before this change the only browse surface lived in
 * the Android-only `:app` module, so nothing Kotlin/Native could compile — let alone render — a
 * catalogue item. The composables under test live in `feature:source-ui` and are the same ones
 * `:app`'s Chitanka / Gutenberg / radio.es screens render; the test lives here because `:shared`
 * is the module whose `commonTest` runs only on `iosSimulatorArm64`. `feature:source-ui` also has
 * an Android host-test compilation, and a Compose UI test cannot run on a bare JVM
 * (`android.os.Build.FINGERPRINT` is null without Robolectric) — the same reason
 * `LibraryTabContentTest` lives here rather than beside the composable it drives.
 *
 * No `stringResource` is exercised on the populated path, so these do not depend on Compose
 * resource packaging inside the Native test binary — only on the composables themselves.
 */
class UnboundedBrowseContentTest {

    private fun item(id: String, title: String, author: String) = CatalogItem(
        id = id,
        rootId = "books",
        title = title,
        author = author,
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
    )

    private val items = listOf(
        item("1", "First Catalogue Title", "First Author"),
        item("2", "Second Catalogue Title", "Second Author"),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rendersCatalogueItemsInTheGrid() = runComposeUiTest {
        setContent {
            UnboundedBrowseContent(
                isOffline = false,
                isLoading = false,
                error = null,
                items = items,
                query = "",
                isPaging = false,
                hasMore = false,
                onLoadMore = {},
                onCoverScaleChange = {},
                itemKey = { it.id },
            ) { catalogItem ->
                WebSourceCatalogItemCard(item = catalogItem, isAudio = false, onClick = {})
            }
        }

        onNodeWithText("First Catalogue Title").assertIsDisplayed()
        onNodeWithText("First Author").assertIsDisplayed()
        onNodeWithText("Second Catalogue Title").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingAnItemCardReportsThatItem() = runComposeUiTest {
        var tapped: String? = null
        setContent {
            UnboundedBrowseContent(
                isOffline = false,
                isLoading = false,
                error = null,
                items = items,
                query = "",
                isPaging = false,
                hasMore = false,
                onLoadMore = {},
                onCoverScaleChange = {},
                itemKey = { it.id },
            ) { catalogItem ->
                WebSourceCatalogItemCard(
                    item = catalogItem,
                    isAudio = false,
                    onClick = { tapped = catalogItem.id },
                )
            }
        }

        onNodeWithText("Second Catalogue Title").performClick()
        // The browse ViewModel turns this into a WebSourceItemGate.openItem + openDetailEvents
        // emission; the card's job is to report the right item.
        assertEquals("2", tapped)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rendersTheCatalogueErrorWhenNothingLoaded() = runComposeUiTest {
        setContent {
            UnboundedBrowseContent(
                isOffline = false,
                isLoading = false,
                error = "Couldn't reach Project Gutenberg. Check your connection and try again.",
                items = emptyList<CatalogItem>(),
                query = "",
                isPaging = false,
                hasMore = false,
                onLoadMore = {},
                onCoverScaleChange = {},
                itemKey = { it.id },
            ) { }
        }

        onNodeWithText("Couldn't reach Project Gutenberg. Check your connection and try again.")
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun asksForTheNextPageWhenTheGridReachesItsEnd() = runComposeUiTest {
        var loadMoreCalls = 0
        setContent {
            UnboundedBrowseContent(
                isOffline = false,
                isLoading = false,
                error = null,
                items = items,
                query = "",
                isPaging = false,
                hasMore = true,
                onLoadMore = { loadMoreCalls++ },
                onCoverScaleChange = {},
                itemKey = { it.id },
            ) { catalogItem ->
                WebSourceCatalogItemCard(item = catalogItem, isAudio = false, onClick = {})
            }
        }
        waitForIdle()

        // Two items is inside the 6-item prefetch threshold, so the grid should already have
        // asked for page 2 — the pagination that makes an unbounded catalogue browsable at all.
        assertTrue(loadMoreCalls > 0, "expected the grid to request the next page")
    }
}
