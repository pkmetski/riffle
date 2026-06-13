package com.riffle.app.feature.reader.readaloud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.audio.PlayerSurfaceActions
import com.riffle.app.feature.audio.PlayerSurfaceState
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sheet mechanics in isolation (no readaloud bundle): the mini player is the peek; expanding slides
 * the full-screen [com.riffle.app.feature.audio.PlayerSurface] overlay up over it; collapsing reveals
 * the untouched peek again. Verifying the live drag gesture is a device task (headless AVDs stall
 * reader WebView nav per project notes), so this drives the shared state directly.
 */
@RunWith(AndroidJUnit4::class)
class ReadaloudPlayerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
            val sheetState = rememberReadaloudSheetState()
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                ReadaloudPeek(sheetState = sheetState) {
                    Text("mini", modifier = Modifier.testTag("test_peek"))
                }
                ReadaloudExpandedOverlay(
                    playerState = sampleState,
                    actions = noopActions,
                    sheetState = sheetState,
                )
                // Test-only triggers standing in for the swipe-up / pull-down gestures.
                Button(onClick = { scope.launch { sheetState.expand() } }, modifier = Modifier.testTag("do_expand")) { Text("e") }
                Button(onClick = { scope.launch { sheetState.collapse() } }, modifier = Modifier.testTag("do_collapse")) { Text("c") }
            }
        }
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
        composeTestRule.onNodeWithTag("do_expand").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("readaloud_player_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readaloud_collapse").assertIsDisplayed()
    }

    @Test
    fun collapsingReturnsToThePeek() {
        setContent()
        composeTestRule.onNodeWithTag("do_expand").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("do_collapse").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("readaloud_player_sheet").assertDoesNotExist()
        composeTestRule.onNodeWithTag("test_peek").assertIsDisplayed()
    }
}
