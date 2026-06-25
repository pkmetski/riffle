package com.riffle.app.feature.audio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test
    fun yearShowsWhenPresent() {
        rule.setContent {
            PlayerSurface(state = baseState.copy(publishedYear = "2014"), actions = noopActions)
        }
        rule.onNodeWithText("Andy Weir").assertIsDisplayed()
        rule.onNodeWithText("2014").assertIsDisplayed()
    }

    @Test
    fun yearIsOmittedWhenNull() {
        rule.setContent {
            PlayerSurface(state = baseState.copy(publishedYear = null), actions = noopActions)
        }
        rule.onNodeWithText("Andy Weir").assertIsDisplayed()
        rule.onNodeWithText("2014").assertDoesNotExist()
    }

    @Test
    fun yearIsOmittedWhenBlank() {
        rule.setContent {
            PlayerSurface(state = baseState.copy(publishedYear = "   "), actions = noopActions)
        }
        rule.onNodeWithText("Andy Weir").assertIsDisplayed()
        rule.onNodeWithText("   ").assertDoesNotExist()
    }
}
