package com.riffle.feature.reader.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.PauseCause
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cadence's two reader surfaces, driven through the real composables on `iosSimulatorArm64` as
 * well as the JVM. `IosEpubReaderScreen` mounts exactly these; before the move they lived in
 * `:app` and iOS had no Cadence control at all.
 */
class CadenceUiTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pillIsAbsentWhileCadenceIsIdle() = runComposeUiTest {
        setContent {
            CadenceHudPill(
                state = CadenceState.Idle,
                labels = SpeedHudLabels.EnglishCadence,
                onPause = {},
                onResume = {},
                onSlower = {},
                onFaster = {},
            )
        }
        onNodeWithTag("cadence_hud_pill").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun runningPillShowsTheLiveSpeedAndNudgesBothWays() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            CadenceHudPill(
                state = CadenceState.Running(AutoScrollSpeed.of(320)),
                labels = SpeedHudLabels.EnglishCadence,
                onPause = { events += "pause" },
                onResume = { events += "resume" },
                onSlower = { events += "slower" },
                onFaster = { events += "faster" },
            )
        }
        onNodeWithTag("cadence_hud_pill").assertIsDisplayed()
        onNodeWithText("320 wpm").assertIsDisplayed()
        onNodeWithTag("cadence_slower").performClick()
        onNodeWithTag("cadence_faster").performClick()
        assertEquals(listOf("slower", "faster"), events)
    }

    /**
     * A paused Cadence keeps its pill: it is the only way to resume or re-speed, and the state
     * carries a speed precisely so the pill can still show it.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pausedPillStaysOnScreenAndResumes() = runComposeUiTest {
        var resumed = false
        setContent {
            CadenceHudPill(
                state = CadenceState.Paused(AutoScrollSpeed.of(250), PauseCause.PanelOpen),
                labels = SpeedHudLabels.EnglishCadence,
                onPause = {},
                onResume = { resumed = true },
                onSlower = {},
                onFaster = {},
            )
        }
        onNodeWithTag("cadence_hud_pill").assertIsDisplayed()
        onNodeWithContentDescription(SpeedHudLabels.EnglishCadence.resume).performClick()
        assertEquals(true, resumed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theToggleAnnouncesStartWhenIdleAndStopWhenRunning() = runComposeUiTest {
        var clicks = 0
        setContent {
            CadenceToggleIcon(isRunning = false, onClick = { clicks++ })
        }
        onNodeWithContentDescription("Start cadence").assertIsDisplayed()
        onNodeWithTag("cadence_toggle").performClick()
        assertEquals(1, clicks)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theRunningToggleAnnouncesStop() = runComposeUiTest {
        setContent { CadenceToggleIcon(isRunning = true, onClick = {}) }
        onNodeWithContentDescription("Stop cadence").assertIsDisplayed()
    }
}
