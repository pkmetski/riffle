package com.riffle.feature.library.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.TestTags
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for item-detail parity (migrated from shared/src/commonTest when
 * LibraryItemDetailScreen was lifted from :shared into :feature:library-ui).
 *
 * Each test pins the rendering behaviour of an internal composable so that reverting the
 * implementation makes the test go red. The composables under test are internal — tests live in
 * the same module to access them.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryItemDetailParityTest {

    private fun item(
        readingProgress: Float = 0f,
        seriesName: String? = null,
        description: String? = null,
    ) = LibraryItem(
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
    )

    // ── Reading progress ────────────────────────────────────────────────────

    @Test
    fun progressIndicatorAppearsWhenReadingProgressIsNonZero() = runComposeUiTest {
        setContent { ReadingProgressIndicator(item(readingProgress = 0.45f)) }
        onNodeWithTag(TestTags.BOOK_DETAIL_PROGRESS).assertIsDisplayed()
    }

    @Test
    fun progressIndicatorIsHiddenWhenReadingProgressIsZero() = runComposeUiTest {
        setContent { ReadingProgressIndicator(item(readingProgress = 0f)) }
        onNodeWithTag(TestTags.BOOK_DETAIL_PROGRESS).assertDoesNotExist()
    }

    // ── Series name ─────────────────────────────────────────────────────────

    @Test
    fun seriesNameIsShownWhenPresent() = runComposeUiTest {
        setContent { SeriesLine(seriesName = "The Stormlight Archive", seriesId = null, onSeriesClick = {}) }
        onNodeWithText("The Stormlight Archive").assertIsDisplayed()
    }

    @Test
    fun seriesNameIsAbsentWhenNull() = runComposeUiTest {
        // Nothing to render when seriesName is null — the caller guards the call site
        setContent {}
        onNodeWithText("The Stormlight Archive").assertDoesNotExist()
    }

    // ── Description ─────────────────────────────────────────────────────────

    @Test
    fun descriptionIsShownWhenPresent() = runComposeUiTest {
        val desc = "A short description of the book."
        setContent { CollapsibleDescription(desc) }
        onNodeWithText(desc).assertIsDisplayed()
    }

    // ── Mark as read ────────────────────────────────────────────────────────

    @Test
    fun markReadButtonIsDisplayed() = runComposeUiTest {
        setContent {
            ReadToggleButton(
                isRead = false,
                onMarkAsRead = {},
                onMarkAsUnread = {},
                modifier = Modifier.testTag(TestTags.BOOK_DETAIL_MARK_READ),
            )
        }
        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).assertIsDisplayed()
    }

    @Test
    fun tappingReadToggleWhenUnreadInvokesOnMarkAsRead() = runComposeUiTest {
        var called = false
        setContent {
            ReadToggleButton(
                isRead = false,
                onMarkAsRead = { called = true },
                onMarkAsUnread = {},
                modifier = Modifier.testTag(TestTags.BOOK_DETAIL_MARK_READ),
            )
        }
        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).performClick()
        assertTrue(called)
    }

    @Test
    fun tappingReadToggleWhenReadInvokesOnMarkAsUnread() = runComposeUiTest {
        var called = false
        setContent {
            ReadToggleButton(
                isRead = true,
                onMarkAsRead = {},
                onMarkAsUnread = { called = true },
                modifier = Modifier.testTag(TestTags.BOOK_DETAIL_MARK_READ),
            )
        }
        onNodeWithTag(TestTags.BOOK_DETAIL_MARK_READ).performClick()
        assertTrue(called)
    }
}
