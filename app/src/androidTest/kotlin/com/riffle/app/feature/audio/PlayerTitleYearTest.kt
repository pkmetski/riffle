package com.riffle.app.feature.audio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.feature.player.CompactDurationLabelTemplates
import com.riffle.feature.player.ui.PlayerChromeLabels
import com.riffle.feature.player.ui.PlayerSurface
import com.riffle.feature.player.ui.PlayerSurfaceActions
import com.riffle.feature.player.ui.PlayerSurfaceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the publication-year line under the author in the audiobook player title block.
 * Regression guard: if [PlayerSurfaceState.publishedYear] is silently dropped from the title
 * block again, the visible year text disappears and these tests catch it before merge.
 */
@RunWith(AndroidJUnit4::class)
class PlayerTitleYearTest {

    @get:Rule val rule = createComposeRule()

    private val baseState = PlayerSurfaceState(
        title = "The Martian",
        author = "Andy Weir",
        durationSec = 39_180.0,
    )

    private val noopActions = PlayerSurfaceActions(
        onSeek = {},
        onTogglePlayPause = {},
        onRewind = {},
        onForward = {},
        onPreviousChapter = {},
        onNextChapter = {},
        onSpeedChange = {},
        onSleepTimerSet = {},
        onSleepTimerCancel = {},
    )

    private val labels = PlayerChromeLabels(
        back = "Back", cannotPlay = "This audiobook can't be played right now.",
        play = "Play", pause = "Pause", previousChapter = "Previous chapter",
        nextChapter = "Next chapter", chapters = "Chapters", bookmarks = "Bookmarks",
        bookmarkCountOne = "1 bookmark", bookmarkCountOther = "%1\$d bookmarks",
        addBookmark = "Add bookmark", removeBookmark = "Remove bookmark",
        newBookmark = "New bookmark", renameBookmark = "Rename bookmark",
        bookmarkOptions = "Bookmark options", rename = "Rename", delete = "Delete",
        save = "Save", cancel = "Cancel", noBookmarksYet = "No bookmarks yet.",
        offlineBookmarksWillSync = "Offline — bookmarks will sync",
        nowPlaying = "Now playing", chapterNumber = "Chapter %1\$d",
        playbackSpeed = "Playback Speed", sleepTimerSheetTitle = "Sleep Timer",
        sleepTimer = "Sleep timer", sleepPillIdle = "Sleep",
        sleepPillEndOfChapter = "End of ch.", endOfChapter = "End of chapter",
        sleepingIn = "Sleeping in %1\$s",
        sleepingAtEndOfChapter = "Sleeping at end of chapter",
        cancelTimer = "Cancel timer", minutesShort = "%1\$d min", audiobook = "Audiobook",
        compactDuration = CompactDurationLabelTemplates(
            minutes = "%1\$dmin", hours = "%1\$dh", hoursMinutes = "%1\$dh %2\$dmin",
        ),
    )

    @Test
    fun yearShowsWhenPresent() {
        rule.setContent {
            PlayerSurface(state = baseState.copy(publishedYear = "2014"), actions = noopActions, labels = labels)
        }
        rule.onNodeWithText("Andy Weir").assertIsDisplayed()
        rule.onNodeWithText("2014").assertIsDisplayed()
    }

    @Test
    fun yearIsOmittedWhenNull() {
        rule.setContent {
            PlayerSurface(state = baseState.copy(publishedYear = null), actions = noopActions, labels = labels)
        }
        rule.onNodeWithText("Andy Weir").assertIsDisplayed()
        rule.onNodeWithText("2014").assertDoesNotExist()
    }

    @Test
    fun yearIsOmittedWhenBlank() {
        rule.setContent {
            PlayerSurface(state = baseState.copy(publishedYear = "   "), actions = noopActions, labels = labels)
        }
        rule.onNodeWithText("Andy Weir").assertIsDisplayed()
        rule.onNodeWithText("   ").assertDoesNotExist()
    }
}
