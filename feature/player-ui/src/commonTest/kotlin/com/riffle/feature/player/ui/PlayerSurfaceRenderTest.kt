package com.riffle.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.player.SleepTimerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real [PlayerSurface] composable. Excluded from the JVM host-test task (Compose's UI
 * harness needs an Android runtime and the repo has no Robolectric) and run for real on
 * `:feature:player-ui:iosSimulatorArm64Test` — so these ARE the iOS-path assertions for the
 * player chrome. Android's own rendering of the same composable is covered on-device by
 * `app/src/androidTest/.../PlayerTitleYearTest`.
 *
 * Every case here is something iOS's old hand-written player could not do: drag to seek, show a
 * countdown, open a speed sheet, open a sleep sheet.
 */
class PlayerSurfaceRenderTest {

    private val labels = PlayerChromeLabels.English

    private fun state(
        positionSec: Double = 600.0,
        durationSec: Double = 3_600.0,
        isPlaying: Boolean = false,
        speed: Float = 1f,
        sleepTimer: SleepTimerMode = SleepTimerMode.None,
        facts: String? = null,
        description: String? = null,
    ) = PlayerSurfaceState(
        title = "A Title",
        author = "An Author",
        isPlaying = isPlaying,
        speed = speed,
        positionSec = positionSec,
        durationSec = durationSec,
        bufferedPositionSec = 900.0,
        currentChapterTitle = "Chapter One",
        chapterStartsSec = listOf(0.0, 1_200.0, 2_400.0),
        bookmarkPositionsSec = listOf(300.0),
        canPreviousChapter = true,
        canNextChapter = true,
        facts = facts,
        description = description,
        sleepTimer = sleepTimer,
        skipIntervalSeconds = 30,
        rewindIntervalSeconds = 15,
    )

    /**
     * The surface inside a phone-sized frame.
     *
     * The skiko test window is 1024x768, where the vertical layout's 90%-of-width square cover is
     * taller than the window and pushes the transport off-screen — `assertIsDisplayed` then fails
     * on controls that are perfectly fine on a real phone.
     */
    @Suppress("ktlint:standard:function-naming")
    @Composable
    private fun Player(
        state: PlayerSurfaceState,
        onSeek: (Double) -> Unit = {},
        onSpeedChange: (Float) -> Unit = {},
        onSleepTimerSet: (SleepTimerMode) -> Unit = {},
        onSleepTimerCancel: () -> Unit = {},
        onTogglePlayPause: () -> Unit = {},
        twoColumn: Boolean = false,
        width: Dp = 390.dp,
        height: Dp = 844.dp,
    ) {
        Box(Modifier.requiredSize(width = width, height = height)) {
            PlayerSurface(
                state = state,
                actions = PlayerSurfaceActions(
                    onSeek = onSeek,
                    onTogglePlayPause = onTogglePlayPause,
                    onRewind = {},
                    onForward = {},
                    onPreviousChapter = {},
                    onNextChapter = {},
                    onSpeedChange = onSpeedChange,
                    onSleepTimerSet = onSleepTimerSet,
                    onSleepTimerCancel = onSleepTimerCancel,
                ),
                labels = labels,
                twoColumn = twoColumn,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * iOS's scrubber was a 4dp tap-only bar. Dragging must scrub, and must keep scrubbing as the
     * finger moves — one seek on touch-down is what a tap already did.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theScrubberSeeksWhileBeingDragged() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        setContent { Player(state(positionSec = 0.0), onSeek = { seeks += it }) }
        onNodeWithTag("player_scrubber").performTouchInput { swipeRight() }

        assertTrue(seeks.size > 1, "a drag must emit a seek per movement, got ${seeks.size}")
        assertTrue(
            seeks.last() > seeks.first(),
            "dragging right must move the playhead forward, got ${seeks.first()} → ${seeks.last()}",
        )
        assertTrue(seeks.all { it in 0.0..3_600.0 }, "seeks must stay inside the book: $seeks")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theScrubberStillSeeksOnASingleTap() = runComposeUiTest {
        val seeks = mutableListOf<Double>()
        setContent { Player(state(positionSec = 0.0), onSeek = { seeks += it }) }
        onNodeWithTag("player_scrubber").performTouchInput { click(Offset(centerX, centerY)) }

        assertEquals(1, seeks.size)
        assertTrue(seeks.single() > 1_000.0, "a centre tap must land near the middle, got ${seeks.single()}")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theRightHandTimeCountsDown() = runComposeUiTest {
        setContent { Player(state(positionSec = 600.0, durationSec = 3_600.0)) }
        onNodeWithTag("player_elapsed").assertIsDisplayed()
        onNodeWithText("10:00").assertIsDisplayed()
        onNodeWithText("-50:00").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedPillOpensTheSpeedSheetAndItsPresetsApply() = runComposeUiTest {
        val speeds = mutableListOf<Float>()
        setContent { Player(state(speed = 1f), onSpeedChange = { speeds += it }) }
        onNodeWithTag("audiobook_speed_pill").assertIsDisplayed().performClick()
        onNodeWithTag("audiobook_speed_display").assertIsDisplayed()
        onNodeWithTag("audiobook_speed_preset_1.5×").performClick()

        assertEquals(listOf(1.5f), speeds)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedSheetNudgesBySingleSteps() = runComposeUiTest {
        val speeds = mutableListOf<Float>()
        setContent { Player(state(speed = 1f), onSpeedChange = { speeds += it }) }
        onNodeWithTag("audiobook_speed_pill").performClick()
        onNodeWithTag("audiobook_speed_plus").performClick()
        onNodeWithTag("audiobook_speed_minus").performClick()

        assertEquals(listOf(1.05f, 0.95f), speeds.map { (it * 100).toInt() / 100f })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSleepPillOpensTheTimerSheetAndArmsAPreset() = runComposeUiTest {
        val armed = mutableListOf<SleepTimerMode>()
        setContent { Player(state(), onSleepTimerSet = { armed += it }) }
        onNodeWithTag("audiobook_sleep_pill").assertIsDisplayed().performClick()
        onNodeWithTag("sleep_timer_sheet").assertIsDisplayed()
        onNodeWithTag("sleep_preset_30").performClick()

        assertEquals(listOf<SleepTimerMode>(SleepTimerMode.CountDown(30 * 60_000L)), armed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSleepSheetCanArmEndOfChapter() = runComposeUiTest {
        val armed = mutableListOf<SleepTimerMode>()
        setContent { Player(state(), onSleepTimerSet = { armed += it }) }
        onNodeWithTag("audiobook_sleep_pill").performClick()
        onNodeWithTag("sleep_end_of_chapter").performClick()

        assertEquals(listOf<SleepTimerMode>(SleepTimerMode.EndOfChapter), armed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anArmedTimerIsShownOnThePillAndCanBeCancelled() = runComposeUiTest {
        var cancelled = 0
        setContent {
            Player(
                state(sleepTimer = SleepTimerMode.CountDown(5 * 60_000L)),
                onSleepTimerCancel = { cancelled++ },
            )
        }
        onNodeWithText("5:00").assertIsDisplayed()
        onNodeWithTag("audiobook_sleep_pill").performClick()
        onNodeWithText("Sleeping in 5:00").assertIsDisplayed()
        onNodeWithContentDescription(labels.cancelTimer).performClick()

        assertEquals(1, cancelled)
    }

    /** The cover is a real image slot, not the procedural placeholder iOS used to draw alone. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theCoverIsRendered() = runComposeUiTest {
        setContent { Player(state()) }
        onNodeWithTag("player_cover").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun thePlayButtonIsLabelledForItsState() = runComposeUiTest {
        var toggles = 0
        setContent { Player(state(isPlaying = true), onTogglePlayPause = { toggles++ }) }
        onNodeWithContentDescription(labels.pause).performClick()
        assertEquals(1, toggles)
    }

    /**
     * A phone in landscape: the cover and the book facts move into a left column beside the
     * transport. iOS had no landscape layout at all and no facts line anywhere.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theTwoColumnLayoutShowsTheBookFactsBesideTheTransport() = runComposeUiTest {
        setContent {
            Player(
                state(facts = "Audiobook · 10h 53m · Science Fiction", description = "<p>A <b>blurb</b>.</p>"),
                twoColumn = true,
                width = 844.dp,
                height = 390.dp,
            )
        }
        onNodeWithTag("player_facts").assertIsDisplayed()
        onNodeWithText("Audiobook · 10h 53m · Science Fiction").assertIsDisplayed()
        onNodeWithTag("player_scrubber").assertIsDisplayed()
    }

    /**
     * The transport jump buttons announce the user's configured interval, using the same two
     * functions the lock-screen transport uses.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSkipButtonsAnnounceTheConfiguredInterval() = runComposeUiTest {
        setContent { Player(state()) }
        onNodeWithContentDescription("Forward 30 seconds").assertIsDisplayed()
        onNodeWithContentDescription("Rewind 15 seconds").assertIsDisplayed()
    }
}
