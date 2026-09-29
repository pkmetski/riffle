package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.LibraryItemDetailUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for iOS item-detail parity with Android.
 *
 * Before these fixes the iOS detail screen showed only the cover, title, author, facets, Read
 * button, and To-Read button. It was missing: reading progress, series name, book description,
 * and the mark-as-read / mark-as-unread control. Each test below pins one of those gaps so that
 * reverting the implementation makes the test go red.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryItemDetailParityTest {

    private fun readyState(
        readingProgress: Float = 0f,
        seriesName: String? = null,
        description: String? = null,
        capabilities: DetailCapabilities = DetailCapabilities.Empty,
    ) = LibraryItemDetailUiState.Ready(
        item = LibraryItem(
            id = "item-1",
            sourceId = "src-1",
            libraryId = "lib-1",
            title = "A Book",
            author = "An Author",
            coverUrl = null,
            readingProgress = readingProgress,
            isCached = false,
            isDownloaded = false,
            ebookFormat = EbookFormat.Epub,
            seriesName = seriesName,
            description = description,
        ),
        capabilities = capabilities,
    )

    // ── Reading progress ────────────────────────────────────────────────────

    @Test
    fun progressIndicatorAppearsWhenReadingProgressIsNonZero() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(readingProgress = 0.45f),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag(TestTags.BOOK_DETAIL_PROGRESS).assertIsDisplayed()
    }

    @Test
    fun progressIndicatorIsHiddenWhenReadingProgressIsZero() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(readingProgress = 0f),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag(TestTags.BOOK_DETAIL_PROGRESS).assertDoesNotExist()
    }

    // ── Series name ─────────────────────────────────────────────────────────

    @Test
    fun seriesNameIsShownWhenPresent() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(seriesName = "The Stormlight Archive"),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("The Stormlight Archive").assertIsDisplayed()
    }

    @Test
    fun seriesNameIsAbsentWhenNull() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(seriesName = null),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        // No series text beside the title
        onNodeWithText("The Stormlight Archive").assertDoesNotExist()
    }

    // ── Description ─────────────────────────────────────────────────────────

    @Test
    fun descriptionIsShownWhenPresent() = runComposeUiTest {
        val desc = "A short description of the book."
        setContent {
            ReadyContent(
                state = readyState(description = desc),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText(desc).assertIsDisplayed()
    }

    // ── Mark as read ────────────────────────────────────────────────────────

    @Test
    fun markReadButtonIsShownWhenCapabilityIsEnabled() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(capabilities = DetailCapabilities.All),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).assertIsDisplayed()
    }

    @Test
    fun markReadButtonIsHiddenWhenCapabilityIsDisabled() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(capabilities = DetailCapabilities.Empty),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).assertDoesNotExist()
    }

    @Test
    fun tappingMarkReadButtonWhenUnreadInvokesOnMarkAsRead() = runComposeUiTest {
        var markReadCalled = false
        setContent {
            ReadyContent(
                state = readyState(readingProgress = 0f, capabilities = DetailCapabilities.All),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onMarkAsRead = { markReadCalled = true },
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).performClick()
        assertTrue(markReadCalled)
    }

    @Test
    fun tappingMarkReadButtonWhenReadInvokesOnMarkAsUnread() = runComposeUiTest {
        var markUnreadCalled = false
        setContent {
            ReadyContent(
                // progress >= 0.99 = book is considered finished / read
                state = readyState(readingProgress = 1f, capabilities = DetailCapabilities.All),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onMarkAsUnread = { markUnreadCalled = true },
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).performClick()
        assertTrue(markUnreadCalled)
    }

    @Test
    fun markReadButtonShowsMarkAsReadLabelWhenBookIsUnread() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(readingProgress = 0.5f, capabilities = DetailCapabilities.All),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("Mark as read").assertIsDisplayed()
    }

    @Test
    fun markReadButtonShowsMarkAsUnreadLabelWhenBookIsRead() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(readingProgress = 1f, capabilities = DetailCapabilities.All),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("Mark as unread").assertIsDisplayed()
    }
}
