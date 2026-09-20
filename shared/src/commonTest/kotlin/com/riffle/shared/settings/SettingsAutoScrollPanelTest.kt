package com.riffle.shared.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Auto-scroll panel on iOS.
 *
 * The whole panel was deleted while the iOS reader had no auto-scroll to configure. It is back
 * because the reader has one now (`AutoScrollController` + `ReadiumSwiftNavigator.scrollByPx`,
 * mounted by `IosEpubReaderScreen`), and both controls must actually write.
 *
 * Runs on `iosSimulatorArm64` as part of `:shared:iosSimulatorArm64Test`.
 */
class SettingsAutoScrollPanelTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun autoScrollPanelOffersTheToggleAndTheSpeedStepper() = runComposeUiTest {
        setContent {
            Column { AutoScrollPanelContent(FormattingPreferences(), onPrefsChange = {}) }
        }
        onNodeWithTag("panel-toggle-Show auto-scroll toggle in reader").assertIsDisplayed()
        onNodeWithText("${FormattingPreferences().autoScrollWpm} WPM").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theReaderToggleSwitchWritesShowAutoScroll() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                AutoScrollPanelContent(
                    FormattingPreferences(showAutoScroll = false),
                    onPrefsChange = { writes += it },
                )
            }
        }
        onNodeWithTag("panel-toggle-Show auto-scroll toggle in reader").performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].showAutoScroll)
    }

    /**
     * The stepper goes through `AutoScrollSpeed.of`, so it snaps and clamps the same way the HUD
     * pill's nudges and the ticker do. A hand-rolled `wpm ± 10` would let Settings store a speed
     * the reader immediately rounds to something else.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedStepperMovesInAutoScrollSpeedSteps() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                AutoScrollPanelContent(
                    FormattingPreferences(autoScrollWpm = 250),
                    onPrefsChange = { writes += it },
                )
            }
        }
        onNodeWithText("+").performClick()
        onNodeWithText("−").performClick()
        assertEquals(listOf(260, 240), writes.map { it.autoScrollWpm })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedStepperClampsAtTheDomainBounds() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                AutoScrollPanelContent(
                    FormattingPreferences(autoScrollWpm = AutoScrollSpeed.MAX_WPM),
                    onPrefsChange = { writes += it },
                )
            }
        }
        onNodeWithText("+").performClick()
        assertEquals(listOf(AutoScrollSpeed.MAX_WPM), writes.map { it.autoScrollWpm })
    }
}
