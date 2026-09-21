package com.riffle.feature.player.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.models.AudiobookBookmark
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The chapters/bookmarks sheet and the bookmark dialog, driven through the real composables on the
 * iOS simulator (`:feature:player-ui:iosSimulatorArm64Test`). None of this existed on iOS before
 * #1072 — a bookmark could not be created, listed, jumped to, renamed or deleted there.
 */
class PlayerChromeRenderTest {

    private val labels = PlayerChromeLabels.English

    private val chapters = listOf(
        AudiobookChapter(index = 0, startSec = 0.0, endSec = 600.0, title = "Prologue"),
        AudiobookChapter(index = 1, startSec = 600.0, endSec = 1_500.0, title = "   "),
    )

    private fun bookmark(id: String, title: String, positionSec: Double) = AudiobookBookmark(
        id = id,
        sourceId = "s",
        itemId = "i",
        positionSec = positionSec,
        title = title,
        createdAt = 0L,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aChapterRowSeeksToItsStart() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        setContent {
            PlayerListSheet(
                content = PlayerListContent.Chapters(
                    items = chapters,
                    currentIndex = 0,
                    onSeek = { seeks += it.startSec },
                ),
                labels = labels,
                onDismiss = {},
            )
        }
        onNodeWithTag("player_list_sheet_title").assertIsDisplayed()
        onNodeWithText("Chapter 2").performClick()
        assertEquals(listOf(600.0), seeks)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theCurrentChapterIsMarkedNowPlaying() = runComposeUiTest {
        setContent {
            PlayerListSheet(
                content = PlayerListContent.Chapters(items = chapters, currentIndex = 0, onSeek = {}),
                labels = labels,
                onDismiss = {},
            )
        }
        onNodeWithContentDescription(labels.nowPlaying).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aBookmarkRowJumpsToItsPosition() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        setContent {
            PlayerListSheet(
                content = PlayerListContent.Bookmarks(
                    items = listOf(bookmark("b1", "The reveal", 1_234.0)),
                    onSeek = { seeks += it.positionSec },
                    onRename = {},
                    onDelete = {},
                ),
                labels = labels,
                onDismiss = {},
            )
        }
        onNodeWithText("The reveal").performClick()
        assertEquals(listOf(1_234.0), seeks)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aBookmarkCanBeRenamedAndDeletedFromItsOverflow() = runComposeUiTest {
        val renamed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        setContent {
            PlayerListSheet(
                content = PlayerListContent.Bookmarks(
                    items = listOf(bookmark("b1", "The reveal", 1_234.0)),
                    onSeek = {},
                    onRename = { renamed += it.id },
                    onDelete = { deleted += it.id },
                ),
                labels = labels,
                onDismiss = {},
            )
        }
        onNodeWithContentDescription(labels.bookmarkOptions).performClick()
        onNodeWithText(labels.rename).performClick()
        assertEquals(listOf("b1"), renamed)

        onNodeWithContentDescription(labels.bookmarkOptions).performClick()
        onNodeWithText(labels.delete).performClick()
        assertEquals(listOf("b1"), deleted)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEmptyBookmarkListSaysSo() = runComposeUiTest {
        setContent {
            PlayerListSheet(
                content = PlayerListContent.Bookmarks(
                    items = emptyList(),
                    onSeek = {},
                    onRename = {},
                    onDelete = {},
                    offlineNote = true,
                ),
                labels = labels,
                onDismiss = {},
            )
        }
        onNodeWithText(labels.noBookmarksYet).assertIsDisplayed()
        onNodeWithText(labels.offlineBookmarksWillSync).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theBookmarkDialogSavesTheEditedTitle() = runComposeUiTest {
        val saved = mutableListOf<String>()
        setContent {
            BookmarkCreateDialog(
                initialTitle = "Prologue · 2:05",
                positionLabel = "2:05 · Prologue",
                suggestions = listOf("Prologue · 2:05", "Prologue", "2:05"),
                labels = labels,
                onConfirm = { saved += it },
                onDismiss = {},
            )
        }
        onNodeWithTag("bookmark_title_field").performTextClearance()
        onNodeWithTag("bookmark_title_field").performTextInput("The reveal")
        onNodeWithTag("bookmark_save").performClick()
        assertEquals(listOf("The reveal"), saved)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aClearedBookmarkTitleFallsBackToTheSuggestedOne() = runComposeUiTest {
        val saved = mutableListOf<String>()
        setContent {
            BookmarkCreateDialog(
                initialTitle = "Prologue · 2:05",
                positionLabel = "2:05 · Prologue",
                suggestions = emptyList(),
                labels = labels,
                onConfirm = { saved += it },
                onDismiss = {},
            )
        }
        onNodeWithTag("bookmark_title_field").performTextClearance()
        onNodeWithTag("bookmark_save").performClick()
        assertEquals(listOf("Prologue · 2:05"), saved)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aSuggestionChipFillsTheTitleField() = runComposeUiTest {
        val saved = mutableListOf<String>()
        setContent {
            BookmarkCreateDialog(
                initialTitle = "Prologue · 2:05",
                positionLabel = "2:05 · Prologue",
                suggestions = listOf("Prologue · 2:05", "Prologue"),
                labels = labels,
                onConfirm = { saved += it },
                onDismiss = {},
            )
        }
        onNodeWithText("Prologue").performClick()
        onNodeWithTag("bookmark_save").performClick()
        assertEquals(listOf("Prologue"), saved)
    }
}
