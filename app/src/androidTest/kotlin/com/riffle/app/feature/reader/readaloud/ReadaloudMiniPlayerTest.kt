package com.riffle.app.feature.reader.readaloud

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadaloudMiniPlayerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun setPlayer(
        canPreviousChapter: Boolean = true,
        canNextChapter: Boolean = true,
        onRewind: () -> Unit = {},
        onForward: () -> Unit = {},
        onPreviousChapter: () -> Unit = {},
        onNextChapter: () -> Unit = {},
    ) {
        rule.setContent {
            ReadaloudMiniPlayer(
                isPlaying = false,
                speed = 1f,
                offlineMessage = false,
                downloadProgress = null,
                canPreviousChapter = canPreviousChapter,
                canNextChapter = canNextChapter,
                containerColor = Color.LightGray,
                contentColor = Color.Black,
                onPlayPause = {},
                onCycleSpeed = {},
                onRewind = onRewind,
                onForward = onForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onClose = {},
            )
        }
    }

    @Test
    fun skipButtons_areDisplayedInThePlayableState() {
        setPlayer()
        rule.onNodeWithTag("readaloud_rewind").assertIsDisplayed()
        rule.onNodeWithTag("readaloud_prev_chapter").assertIsDisplayed()
        rule.onNodeWithTag("readaloud_next_chapter").assertIsDisplayed()
        rule.onNodeWithTag("readaloud_forward").assertIsDisplayed()
    }

    @Test
    fun skipButtons_invokeTheirCallbacks() {
        var rewinds = 0
        var forwards = 0
        var prevs = 0
        var nexts = 0
        setPlayer(
            onRewind = { rewinds++ },
            onForward = { forwards++ },
            onPreviousChapter = { prevs++ },
            onNextChapter = { nexts++ },
        )
        rule.onNodeWithTag("readaloud_rewind").performClick()
        rule.onNodeWithTag("readaloud_forward").performClick()
        rule.onNodeWithTag("readaloud_prev_chapter").performClick()
        rule.onNodeWithTag("readaloud_next_chapter").performClick()
        assertEquals(1, rewinds)
        assertEquals(1, forwards)
        assertEquals(1, prevs)
        assertEquals(1, nexts)
    }

    @Test
    fun nextChapter_isDisabledAtTheLastChapter() {
        setPlayer(canNextChapter = false)
        rule.onNodeWithTag("readaloud_next_chapter").assertIsNotEnabled()
    }

    @Test
    fun skipButtons_areAbsentWhileOffline() {
        rule.setContent {
            ReadaloudMiniPlayer(
                isPlaying = false,
                speed = 1f,
                offlineMessage = true,
                downloadProgress = null,
                canPreviousChapter = true,
                canNextChapter = true,
                containerColor = Color.LightGray,
                contentColor = Color.Black,
                onPlayPause = {},
                onCycleSpeed = {},
                onRewind = {},
                onForward = {},
                onPreviousChapter = {},
                onNextChapter = {},
                onClose = {},
            )
        }
        rule.onNodeWithTag("readaloud_rewind").assertDoesNotExist()
        rule.onNodeWithTag("readaloud_next_chapter").assertDoesNotExist()
    }
}
