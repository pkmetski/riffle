package com.riffle.app.feature.reader.readaloud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.audio.PlayerSurfaceActions
import com.riffle.app.feature.audio.PlayerSurfaceState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sheet mechanics in isolation (no readaloud bundle): the mini player is the peek; expanding slides
 * the full-screen [com.riffle.app.feature.audio.PlayerSurface] overlay up over it; collapsing reveals
 * the untouched peek again. Verifying the live drag gesture is a device task (headless AVDs stall the
 * reader WebView per project notes), so this drives the shared state directly via the animation-free
 * snapTo path (nudge) — exactly the value a settled drag lands on.
 */
@RunWith(AndroidJUnit4::class)
class ReadaloudPlayerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Constructed (not remembered) so the test thread can drive it directly.
    private val sheetState = ReadaloudSheetState()

    private val sampleState = PlayerSurfaceState(
        title = "The Wind in the Willows",
        author = "Kenneth Grahame",
        isPlaying = true,
        durationSec = 600.0,
        positionSec = 120.0,
        chapterStartsSec = listOf(0.0, 300.0),
        currentChapterTitle = "Chapter 1 of 2",
        canNextChapter = true,
    )

    private val noopActions = PlayerSurfaceActions(
        onSeek = {}, onTogglePlayPause = {}, onRewind = {}, onForward = {},
        onPreviousChapter = {}, onNextChapter = {}, onSpeedChange = {},
    )

    private fun setContent() {
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                ReadaloudPeek(sheetState = sheetState) {
                    Text("mini", modifier = Modifier.testTag("test_peek"))
                }
                ReadaloudExpandedOverlay(
                    playerState = sampleState,
                    actions = noopActions,
                    sheetState = sheetState,
                )
            }
        }
    }

    /** Snap the sheet to a progress without animation — the resting value a finished drag produces. */
    private fun setProgress(target: Float) {
        runBlocking { sheetState.nudge(target - sheetState.progress.value) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun collapsedShowsThePeekAndHidesTheFullPlayer() {
        setContent()
        composeTestRule.onNodeWithTag("test_peek").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readaloud_player_sheet").assertDoesNotExist()
    }

    @Test
    fun expandingShowsTheFullPlayerSurface() {
        setContent()
        setProgress(1f)
        composeTestRule.onNodeWithTag("readaloud_player_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readaloud_collapse").assertIsDisplayed()
    }

    @Test
    fun collapsingReturnsToThePeek() {
        setContent()
        setProgress(1f)
        composeTestRule.onNodeWithTag("readaloud_player_sheet").assertExists()
        setProgress(0f)
        composeTestRule.onNodeWithTag("readaloud_player_sheet").assertDoesNotExist()
        composeTestRule.onNodeWithTag("test_peek").assertIsDisplayed()
    }
}
