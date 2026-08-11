package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reading-time estimate (EPUB) and extracted page count (PDF) resolve asynchronously,
 * seconds after the detail screen first renders. The facts line must reserve its space from the
 * first frame so the Read button never moves when the value lands — a shifting button swallows
 * in-flight taps (the post-#650 harness flake family, and a real-user mis-tap hazard).
 */
@RunWith(AndroidJUnit4::class)
class LibraryItemDetailReadButtonStabilityTest {

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
    )

    @Test
    fun readButtonDoesNotMoveWhenReadingTimeEstimateArrives() {
        var estimatedSec by mutableStateOf<Long?>(null)
        composeTestRule.setContent {
            LibraryItemDetailContent(
                item = item,
                seriesId = null,
                onFacet = { _, _ -> },
                onSeriesClick = { _, _ -> },
                isInToRead = false,
                token = "",
                downloadState = DownloadState.NotDownloaded,
                isCachedOrDownloaded = false,
                isOffline = false,
                readaloudDownloadState = null,
                estimatedTotalReadingTimeSec = estimatedSec,
                onReadItem = {},
                onMarkAsRead = {},
                onMarkAsUnread = {},
                onToggleToRead = {},
                onDownload = {},
                onRemove = {},
            )
        }
        composeTestRule.waitForIdle()
        val before = composeTestRule.onNodeWithText("Read").getUnclippedBoundsInRoot()

        estimatedSec = 7_560L
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2h 6m estimated").assertIsDisplayed()

        val after = composeTestRule.onNodeWithText("Read").getUnclippedBoundsInRoot()
        assert(before == after) {
            "Read button moved when the reading-time estimate arrived: before=$before after=$after"
        }
    }
}
