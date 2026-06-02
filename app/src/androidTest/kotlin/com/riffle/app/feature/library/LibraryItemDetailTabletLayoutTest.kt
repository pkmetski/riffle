package com.riffle.app.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.harness.TabletLayout
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Library Item Detail Screen's tablet layout (ADR 0020): two panes
 * within a single destination, left pane fixed and right pane scrolling
 * independently.
 */
@TabletLayout
@RunWith(AndroidJUnit4::class)
class LibraryItemDetailTabletLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val item = LibraryItem(
        id = "i1",
        libraryId = "lib1",
        title = "A Test Book",
        author = "Test Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        description = LONG_DESCRIPTION,
        seriesName = "A Series #1",
        publishedYear = "2026",
        genres = listOf("Fantasy", "Adventure"),
        publisher = "Riffle Press",
    )

    @Test
    fun bothPanesRenderAndScrollingRightPaneDoesNotMoveLeft() {
        composeTestRule.setContent {
            LibraryItemDetailContentTablet(
                item = item,
                isInToRead = false,
                token = "",
                downloadState = DownloadState.NotDownloaded,
                isReadaloud = false,
                readaloudFooter = null,
                isCachedOrDownloaded = false,
                isOffline = false,
                readaloudDownloadState = null,
                onReadItem = {},
                onMarkAsRead = {},
                onMarkAsUnread = {},
                onToggleToRead = {},
                onDownload = {},
                onRemove = {},
                onUnlinkReadaloud = {},
            )
        }

        val leftPane = composeTestRule.onNodeWithTag(LIBRARY_ITEM_DETAIL_LEFT_PANE_TAG)
        val rightPane = composeTestRule.onNodeWithTag(LIBRARY_ITEM_DETAIL_RIGHT_PANE_TAG)

        leftPane.assertIsDisplayed()
        rightPane.assertIsDisplayed()
        // Left-pane content present.
        composeTestRule.onNodeWithText("A Test Book").assertIsDisplayed()
        composeTestRule.onNodeWithText("By Test Author").assertIsDisplayed()
        // Right-pane content present.
        composeTestRule.onNodeWithText("Summary").assertIsDisplayed()

        val leftBeforeBounds = leftPane.getUnclippedBoundsInRoot()
        val titleBeforeBounds = composeTestRule.onNodeWithText("A Test Book").getUnclippedBoundsInRoot()

        rightPane.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        val leftAfterBounds = leftPane.getUnclippedBoundsInRoot()
        val titleAfterBounds = composeTestRule.onNodeWithText("A Test Book").getUnclippedBoundsInRoot()

        assert(leftBeforeBounds == leftAfterBounds) {
            "Left pane bounds changed after scrolling right pane: before=$leftBeforeBounds after=$leftAfterBounds"
        }
        assert(titleBeforeBounds == titleAfterBounds) {
            "Left-pane title moved after scrolling right pane: before=$titleBeforeBounds after=$titleAfterBounds"
        }
    }
}

private val LONG_DESCRIPTION = buildString {
    repeat(80) {
        append("Paragraph $it. ")
        append("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ")
        append("Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. ")
        append('\n')
    }
}
