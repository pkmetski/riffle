package com.riffle.feature.reader.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.PauseCause
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The auto-scroll HUD pill, driven through the real composable on `iosSimulatorArm64` as well as
 * the JVM. `IosEpubReaderScreen` mounts this exact pill; before the move it lived in `:app` and
 * iOS had no speed control at all.
 */
class AutoScrollHudPillTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pillIsAbsentWhileAutoScrollIsIdle() = runComposeUiTest {
        setContent {
            AutoScrollHudPill(
                state = AutoScrollState.Idle,
                labels = SpeedHudLabels.English,
                onPause = {},
                onResume = {},
                onSlower = {},
                onFaster = {},
            )
        }
        onNodeWithTag("auto_scroll_hud_pill").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun runningPillShowsTheLiveSpeedAndNudgesBothWays() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            AutoScrollHudPill(
                state = AutoScrollState.Running(AutoScrollSpeed.of(320)),
                labels = SpeedHudLabels.English,
                onPause = { events += "pause" },
                onResume = { events += "resume" },
                onSlower = { events += "slower" },
                onFaster = { events += "faster" },
            )
        }
        onNodeWithTag("auto_scroll_hud_pill").assertIsDisplayed()
        onNodeWithText("320 wpm").assertIsDisplayed()
        onNodeWithTag("auto_scroll_slower").performClick()
        onNodeWithTag("auto_scroll_faster").performClick()
        assertEquals(listOf("slower", "faster"), events)
    }

    /**
     * Pausing from the pill deliberately keeps the pill on screen — it is the only way back. A
     * `Paused` state that hid the pill would strand the user with a stopped reader and no
     * control, which is what `isHudPillVisible` exists to prevent.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pausedFromThePillKeepsThePillOnScreen() = runComposeUiTest {
        setContent {
            AutoScrollHudPill(
                state = AutoScrollState.Paused(AutoScrollSpeed.of(320), PauseCause.UserPausedPill),
                labels = SpeedHudLabels.English,
                onPause = {},
                onResume = {},
                onSlower = {},
                onFaster = {},
            )
        }
        onNodeWithTag("auto_scroll_hud_pill").assertIsDisplayed()
    }
}
