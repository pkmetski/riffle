package com.riffle.app.feature.reader.readaloud

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
        speed: Float = 1f,
        canPreviousChapter: Boolean = true,
        canNextChapter: Boolean = true,
        onSpeedChange: (Float) -> Unit = {},
        onRewind: () -> Unit = {},
        onForward: () -> Unit = {},
        onPreviousChapter: () -> Unit = {},
        onNextChapter: () -> Unit = {},
    ) {
        rule.setContent {
            ReadaloudMiniPlayer(
                isPlaying = false,
                speed = speed,
                offlineMessage = false,
                downloadProgress = null,
                canPreviousChapter = canPreviousChapter,
                canNextChapter = canNextChapter,
                containerColor = Color.LightGray,
                contentColor = Color.Black,
                onPlayPause = {},
                onSpeedChange = onSpeedChange,
                onRewind = onRewind,
                onForward = onForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                skipIntervalSeconds = 30,
                rewindIntervalSeconds = 15,
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
    fun tappingTheValue_opensTheSpeedSheet() {
        setPlayer(speed = 1.4f)
        rule.onNodeWithTag("readaloud_speed_slider_card").assertDoesNotExist()
        rule.onNodeWithTag("readaloud_speed").performClick()
        rule.onNodeWithTag("readaloud_speed_slider").assertIsDisplayed()
    }

    @Test
    fun presetChip_setsThatSpeed() {
        var requested = -1f
        setPlayer(speed = 1.4f, onSpeedChange = { requested = it })
        rule.onNodeWithTag("readaloud_speed").performClick()
        rule.onNodeWithTag("readaloud_speed_preset_1.25×").performClick()
        assertEquals(1.25f, requested, 0.0001f)
    }

    @Test
    fun speedLabel_showsGranularValues() {
        setPlayer(speed = 1.4f)
        rule.onNodeWithText("1.4×").assertIsDisplayed()
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
                onSpeedChange = {},
                onRewind = {},
                onForward = {},
                onPreviousChapter = {},
                onNextChapter = {},
                skipIntervalSeconds = 30,
                rewindIntervalSeconds = 15,
                onClose = {},
            )
        }
        rule.onNodeWithTag("readaloud_rewind").assertDoesNotExist()
        rule.onNodeWithTag("readaloud_next_chapter").assertDoesNotExist()
    }
}
